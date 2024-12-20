/*
 * Copyright (C) 2009 Search Solution Corporation. All rights reserved by Search Solution. 
 *
 * Redistribution and use in source and binary forms, with or without modification, 
 * are permitted provided that the following conditions are met: 
 *
 * - Redistributions of source code must retain the above copyright notice, 
 *   this list of conditions and the following disclaimer. 
 *
 * - Redistributions in binary form must reproduce the above copyright notice, 
 *   this list of conditions and the following disclaimer in the documentation 
 *   and/or other materials provided with the distribution. 
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors 
 *   may be used to endorse or promote products derived from this software without 
 *   specific prior written permission. 
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, 
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY 
 * OF SUCH DAMAGE. 
 *
 */
package com.cubrid.cubridmigration.core.engine.importer.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.cubrid.cubridmigration.core.common.Closer;
import com.cubrid.cubridmigration.core.common.DBUtils;
import com.cubrid.cubridmigration.core.dbobject.Column;
import com.cubrid.cubridmigration.core.dbobject.FK;
import com.cubrid.cubridmigration.core.dbobject.Index;
import com.cubrid.cubridmigration.core.dbobject.PK;
import com.cubrid.cubridmigration.core.dbobject.Record;
import com.cubrid.cubridmigration.core.dbobject.Record.ColumnValue;
import com.cubrid.cubridmigration.core.dbobject.Sequence;
import com.cubrid.cubridmigration.core.dbobject.Table;
import com.cubrid.cubridmigration.core.dbobject.View;
import com.cubrid.cubridmigration.core.engine.JDBCConManager;
import com.cubrid.cubridmigration.core.engine.MigrationContext;
import com.cubrid.cubridmigration.core.engine.ThreadUtils;
import com.cubrid.cubridmigration.core.engine.config.MigrationConfiguration;
import com.cubrid.cubridmigration.core.engine.config.SourceTableConfig;
import com.cubrid.cubridmigration.core.engine.event.ImportGraphRecordsEvent;
import com.cubrid.cubridmigration.core.engine.event.SingleRecordErrorEvent;
import com.cubrid.cubridmigration.core.engine.exception.JDBCConnectErrorException;
import com.cubrid.cubridmigration.core.engine.exception.NormalMigrationException;
import com.cubrid.cubridmigration.core.engine.importer.Importer;
import com.cubrid.cubridmigration.graph.dbobj.Edge;
import com.cubrid.cubridmigration.graph.dbobj.Vertex;
import com.cubrid.cubridmigration.graph.stmt.GraphParameterSetter;

public class GraphJDBCImporter extends
		Importer {

	private final JDBCConManager connectionManager;
	private final MigrationConfiguration config;
	private final GraphParameterSetter parameterSetter;

	public GraphJDBCImporter(MigrationContext mrManager) {
		super(mrManager);
		this.parameterSetter = mrManager.getGraphParamSetter();
		this.config = mrManager.getConfig();
		this.connectionManager = mrManager.getConnManager();
	}

	public int importEdge(Edge e, List<Record> records) {
		int retryCount = 0;
		while (true) {
			try {
				if (e.getEdgeType() == Edge.JOINTABLE_TYPE) {
					return createJoinEdgeImport(e, records);
				} else {
					if (config.isCdc()) {
						return createEdgeImportForCDC(e, records);
					}
					
					return createEdgeImport(e);
				}
			} catch (JDBCConnectErrorException ex) {
				if (retryCount < 3) {
					retryCount++;
					ThreadUtils.threadSleep(2000, eventHandler);
				} else {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(e, e.getfkCol2RefMappingSize(), ex, null));
					return 0;
				}
			} catch (Exception exception) {
				eventHandler.handleEvent(new ImportGraphRecordsEvent(e, e.getfkCol2RefMappingSize(), exception, null));
				return 0;
			}
		}
	}
	
	private int createEdgeImportForCDC(Edge edge, List<Record> records) throws SQLException {
		int result = 0;
		boolean prvAutoCommitStatus = false;
		Connection conn = connectionManager.getTargetConnection(); //NOPMD
		if (conn.getAutoCommit()) {
			prvAutoCommitStatus = true;
			conn.setAutoCommit(false);
		}
		
		PreparedStatement stmt = null; //NOPMD
		
		try {
			for (Record rec : records) {
				
				for (int i = 0; i < edge.getfkCol2RefMapping().size(); i++){
					String sql = getCDCFkEdge(edge, i);
					
					try {
						stmt = conn.prepareStatement(sql);
						
 						parameterSetter.setFkRecord2Statement(edge.getREFColumnNames(edge.getFKColumnNames().get(i)), rec, stmt);
						
						//int ret = stmt.executeUpdate();
						ResultSet rs = stmt.executeQuery();
						if (rs.next()) {
							result += rs.getInt("count(r)");
						}
						DBUtils.commit(conn);
						
						if (result > 0) {
							eventHandler.handleEvent(new ImportGraphRecordsEvent(edge, result));
						}
						
					} catch (SQLException ex) {
						if (isConnectionCutDown(ex)) {
							throw new JDBCConnectErrorException(ex);
						}
						ex.printStackTrace();
						DBUtils.rollback(conn);
					}
				}
			}
			
		} finally {
			Closer.close(stmt);
			if (prvAutoCommitStatus) {
				conn.setAutoCommit(true);
			}
			connectionManager.closeTar(conn);
		}
		
		return result;
	}

	private int createEdgeImport(Edge e) throws SQLException {
		int result = 0;
		boolean prvAutoCommitStatus = false;
		Connection conn = connectionManager.getTargetConnection(); //NOPMD
		if (conn.getAutoCommit()) {
			prvAutoCommitStatus = true;
			conn.setAutoCommit(false);
		}
		PreparedStatement stmt = null; //NOPMD
			try {
				for (int i=0 ; i < e.getfkCol2RefMappingSize(); i++) {
					String sql = getTargetInsertEdge(e, i);
					try {
						stmt = conn.prepareStatement(sql);
						//int ret = stmt.executeUpdate();
						ResultSet rs = stmt.executeQuery();
						if (rs.next()) {
							result += rs.getInt("count(r)");
						}
						DBUtils.commit(conn);
						
						if (result > 0) {
							eventHandler.handleEvent(new ImportGraphRecordsEvent(e, result));
						}
						
					} catch (SQLException ex) {
						if (isConnectionCutDown(ex)) {
							throw new JDBCConnectErrorException(ex);
						}
						DBUtils.rollback(conn);
					}
				}
			} finally {
				Closer.close(stmt);
				if (prvAutoCommitStatus) {
					conn.setAutoCommit(true);
				}
				connectionManager.closeTar(conn);
			}
		return result;
	}
	
	private int createJoinEdgeImport(Edge e, List<Record> records) throws SQLException {
		int result = 0;
		int resultTotal = 0;
		boolean prvAutoCommitStatus = false;
		Connection conn = connectionManager.getTargetConnection();
		if (conn.getAutoCommit()) {
			prvAutoCommitStatus = true;
			conn.setAutoCommit(false);
		}
		PreparedStatement stmt = null;
		ResultSet rs1 = null;
		String sql = getTargetInsertJoinEdge(e);
			try {
				if (sql == null) {
					try {
						Exception ex = new Exception("There is not a single supported column in the table.");
						throw ex;
					} catch (Exception ex) {
						ex.printStackTrace();
						eventHandler.handleEvent(new SingleRecordErrorEvent(null, ex));
					}
				}
				
				stmt = conn.prepareStatement(sql);
				
				for (Record rc : records) {
					if (rc == null) {
						continue;
					}
					
					parameterSetter.setEdgeRecord2Statement(e, rc, stmt);

					rs1 = stmt.executeQuery();
					
					if (rs1.next()) {
						result = rs1.getInt("count(r)");
						resultTotal += result;
					}
					
					stmt.clearParameters();
				}
				DBUtils.commit(conn);
				if (resultTotal > 0) {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(e, resultTotal));
				}
			} catch (SQLException ex) {
				ex.printStackTrace();
				
				if (isConnectionCutDown(ex)) {
					throw new JDBCConnectErrorException(ex);
				}
				DBUtils.rollback(conn);
				//If SQL has errors, write the records to a SQL files.
			} catch (Exception eee) {
				eee.printStackTrace();
			}
			finally {
				Closer.close(stmt);
				if (prvAutoCommitStatus) {
					conn.setAutoCommit(true);
				}
				connectionManager.closeTar(conn);
			}
		return resultTotal;
	}
	
	public int importCDCObject(Vertex vertex, Edge edge, List<Record> vRecords) {
		// CDC import vertex and edge
		int retryCount = 0;
		mrManager.getStatusMgr().addImpCount(vertex.getOwner(), vertex.getVertexLabel(), vRecords.size());
		while (true) {
			try {
				// CDC make FK edge here
				return createCDCEdge(vertex, edge, vRecords);
				
			} catch (JDBCConnectErrorException ex) {
				if (retryCount < 3) {
					retryCount++;
					ThreadUtils.threadSleep(2000, eventHandler);
				} else {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(vertex, vRecords.size(), ex, null));
					return 0;
				}
			} catch (Exception ex) {
				eventHandler.handleEvent(new ImportGraphRecordsEvent(vertex, vRecords.size(), ex, null));
				return 0;
			}
		}
	}
	
	private int createCDCEdge(Vertex ver, Edge edge, List<Record> vRecs) throws SQLException {
		int result = 0;
		boolean prvAutoCommitStatus = false;
		Connection conn = connectionManager.getTargetConnection(); //NOPMD
		if (conn.getAutoCommit()) {
			prvAutoCommitStatus = true;
			conn.setAutoCommit(false);
		}
		
		PreparedStatement stmt = null; //NOPMD
		
		try {
			for (Record rec : vRecs) {
				
				for (int i = 0; i < edge.getfkCol2RefMapping().size(); i++){
					String sql = getCDCFkEdge(edge, i);
					
					try {
						stmt = conn.prepareStatement(sql);
						
 						parameterSetter.setFkRecord2Statement(edge.getFKColumnNames().get(i), rec, stmt);
						
						//int ret = stmt.executeUpdate();
						ResultSet rs = stmt.executeQuery();
						if (rs.next()) {
							result += rs.getInt("count(r)");
						}
						DBUtils.commit(conn);
						
						if (result > 0) {
							eventHandler.handleEvent(new ImportGraphRecordsEvent(edge, result));
						}
						
					} catch (SQLException ex) {
						if (isConnectionCutDown(ex)) {
							throw new JDBCConnectErrorException(ex);
						}
						ex.printStackTrace();
						DBUtils.rollback(conn);
					}
				}
			}
			
		} finally {
			Closer.close(stmt);
			if (prvAutoCommitStatus) {
				conn.setAutoCommit(true);
			}
			connectionManager.closeTar(conn);
		}
		
		return result;
	}
	
	private String getCDCFkEdge(Edge edge, int index) {
		StringBuffer buffer = new StringBuffer();
		
		buffer.append("match (n:").append(edge.getStartVertexName()).append("), ");
		buffer.append("(m:").append(edge.getEndVertexName()).append(")");
		buffer.append(" where ");
		buffer.append("n.").append(edge.getFKColumnNames().get(index)).append(" = ");
		buffer.append("m.").append(edge.getREFColumnNames(edge.getFKColumnNames().get(index))).append(" ");
		buffer.append("and m.").append(edge.getREFColumnNames(edge.getFKColumnNames().get(index))).append(" = ");
		buffer.append("? ");
		buffer.append("create (n)-[r:").append(edge.getEdgeLabel()).append("]->(m) return count(r)");
		
		return buffer.toString();
	}
	
	private String getTargetInsertEdge(Edge e, int idx) {
		StringBuffer buf = new StringBuffer("MATCH (n:").append(e.getStartVertexName()).append("),");
		buf.append("(m:").append(e.getEndVertexName()).append(")");
		buf.append(" where ");
		buf.append("n.").append(e.getFKColumnNames().get(idx)).append(" = ");
		buf.append("m.").append(e.getREFColumnNames(e.getFKColumnNames().get(idx))).append(" ");
		
		if (e.getEdgeType() == Edge.TWO_WAY_TYPE) {
			buf.append("create (m)-[r:").append(e.getEdgeLabel()).append("]->(n) return count(r)");
		} else {
			buf.append("create (n)-[r:").append(e.getEdgeLabel()).append("]->(m) return count(r)");
		}
		
		
		return buf.toString();
	}
	
	private String getTargetInsertJoinEdge(Edge e) {
		StringBuffer buffer = new StringBuffer();
		buffer.append("Match (n:").append(e.getStartVertexName());
		buffer.append("), (m:").append(e.getEndVertexName());
		buffer.append(")");
		
		if (e.getStartVertexName().equals(e.getEndVertexName())) {
			buffer.append(" where n.").append(e.getREFColumnNames(e.getFKColumnNames().get(1))).append(" = ");
		} else {
//			buffer.append(" where n.").append(e.getFKColumnNames().get(0)).append(" = ");
			buffer.append(" where n.").append(e.getREFColumnNames(e.getFKColumnNames().get(0))).append(" = ");
		}
		
		buffer.append("?");
		buffer.append(" and m.").append(e.getREFColumnNames(e.getFKColumnNames().get(1))).append(" = ");
		buffer.append("?");
		
		buffer.append(" create (n)-[r:").append(e.getEdgeLabel().replaceAll(" ", "_"));
		if (e.getColumnList() != null) {
			buffer.append('{');
			
			for (int i = 0; i < e.getColumnList().size(); i++) {
				buffer.append(e.getColumnList().get(i).getName() + ":");
				
				if (e.getColumnList().get(i).getGraphDataType().equals("date")) {
					buffer.append("date(");
					buffer.append('?');
					buffer.append(')');
				} else if (e.getColumnList().get(i).getGraphDataType().equals("datetime")){
					buffer.append("datetime(");
					buffer.append('?');
					buffer.append(')');
				} else {
					buffer.append('?');
				}
				
				if (i < e.getColumnList().size() - 1) {
					buffer.append(", ");
				}
			}
			
			buffer.append('}');
		}
		
		buffer.append("]");
		buffer.append("->(m) ");
		buffer.append("return count(r)");
		return buffer.toString();
	}

	////////////////////////////////////////////////////////
	///////////////////////  Vertex ////////////////////////
	////////////////////////////////////////////////////////
	
	public int importVertex(Vertex v, List<Record> records) {
		int retryCount = 0;
		mrManager.getStatusMgr().addImpCount(v.getOwner(), v.getVertexLabel(), records.size());
		while (true) {
			try {
				return simpleVertexImportRecords(v, records);
			} catch (JDBCConnectErrorException ex) {
				if (retryCount < 3) {
					retryCount++;
					ThreadUtils.threadSleep(2000, eventHandler);
				} else {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(v, records.size(), ex, null));
					return 0;
				}
			} catch (Exception e) {
				eventHandler.handleEvent(new ImportGraphRecordsEvent(v, records.size(), e, null));
				return 0;
			}
		}
	}

	/**
	 * Import with no retry.
	 * 
	 * @param Vertex Table List
	 * @param records List<Record>
	 * @return success record count
	 * @throws SQLException when SQL error
	 */
	private int simpleVertexImportRecords(Vertex v, List<Record> records) throws SQLException {
		//Auto commit is false by default.
		Connection conn = connectionManager.getTargetConnection(); //NOPMD
		boolean prvAutoCommitStatus = false;
		if (conn.getAutoCommit()) {
			prvAutoCommitStatus = true;
			conn.setAutoCommit(false);
		}
		PreparedStatement stmt = null; //NOPMD
		int result = 0;
		try {
			String sql = getTargetInsertVertex(v);
			try {
				stmt = conn.prepareStatement(sql);

				if (sql == null) {
					try {
						Exception e = new Exception("There is not a single supported column in the table.");
						throw e;
					} catch (Exception e) {
						eventHandler.handleEvent(new SingleRecordErrorEvent(null, e));
					}
				}
				
				for (Record rc : records) {
					if (rc == null) {
						continue;
					}
					try {
						Record trec = createTargetRecord(v, rc);
						parameterSetter.setRecord2Statement(trec, stmt);
						stmt.addBatch();
					} catch (SQLException ex) {
						//print exception
						
						ex.printStackTrace();
						
						if (isConnectionCutDown(ex)) {
							throw new JDBCConnectErrorException(ex);
						}
						eventHandler.handleEvent(new SingleRecordErrorEvent(rc, ex));
					} catch (Exception ex) {
						ex.printStackTrace();
						eventHandler.handleEvent(new SingleRecordErrorEvent(rc, ex));
					}
				}
				int[] exers = stmt.executeBatch();
				DBUtils.commit(conn);
				for (int rs : exers) {
					result += rs;
				}
				if (result != records.size()) {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(v, records.size() - result,
							new NormalMigrationException(ERROR_RECORD_MSG), null));
				}
				if (result > 0) {
					eventHandler.handleEvent(new ImportGraphRecordsEvent(v, result));
				}
			} catch (SQLException ex) {
				
				ex.printStackTrace();
				
				if (isConnectionCutDown(ex)) {
					throw new JDBCConnectErrorException(ex);
				}
				DBUtils.rollback(conn);
				//If SQL has errors, write the records to a SQL files.
				//String file = null;
				if (config.isWriteErrorRecords()) {
					List<Record> errorRecords = new ArrayList<Record>();
					for (Record rc : records) {
						if (rc == null) {
							continue;
						}
						Record trec = createTargetRecord(v, rc);
						if (trec != null) {
							errorRecords.add(trec);
						}
					}
					//file = errorRecordsWriter.writeSQLRecords(stc, errorRecords);
				}
				//eventHandler.handleEvent(new ImportGraphRecordsEvent(v, null, records.size(), ex, file));
			}
		} finally {
			Closer.close(stmt);
			if (prvAutoCommitStatus) {
				conn.setAutoCommit(true);
			}
			connectionManager.closeTar(conn);
		}
		return result;
	}
	
	private String getTargetInsertVertex(Vertex v) {
		int supportColumCount = 0;
		StringBuffer buffer = new StringBuffer("CREATE (n: ").append(v.getVertexLabel()).append(" {");
		List<Column> columns = v.getColumnList();
		int len = columns.size();
		for (int i = 0; i < len; i++) {
			
			if (!columns.get(i).isSelected()) {
				continue;
			}
			
			if (!columns.get(i).getSupportGraphDataType()) {
				continue;
			}
			
			supportColumCount++;
			
			if (i > 0) {
				buffer.append(", ");
			}
			String columnName = columns.get(i).getName();
			columnName = columnName.replaceAll("\"", "");
			buffer.append(columnName).append(':');
			
			if (columns.get(i).getGraphDataType().equals("datetime")) {
				buffer.append("datetime(");
				buffer.append('?');
				buffer.append(")");
			} else if (columns.get(i).getGraphDataType().equals("date")) {
				buffer.append("date(");
				buffer.append('?');
				buffer.append(")");
			} else {
				buffer.append('?');
			}
		}
		
		if (supportColumCount == 0) {
			return null;
		}
		
		buffer.append("}");
		buffer.append(")");
		buffer.append(" return n");
		return buffer.toString();
	}

	/**
	 * If database connect is closed by server, it needs retry 5 times
	 * 
	 * @param ex the exception raised.
	 * @return true:need retry.
	 */
	private boolean isConnectionCutDown(SQLException ex) {
		String message = ex.getMessage();
		return message.indexOf("Connection or Statement might be closed") >= 0
				|| message.indexOf("Cannot communicate with the broker") >= 0
				|| ex.getErrorCode() == -2019 || ex.getErrorCode() == -21003
				&& ex.getErrorCode() == -2003;
	}
	
	/**
	 * Create a target record by source record
	 * 
	 * @param v Vertex
	 * @param rrec source record
	 * @return Target record
	 */
//		Record trec = new Record();
	private Record createTargetRecord(Vertex v, Record rrec) {
//		Map<String, Object> recordMap = rrec.getColumnValueMap();
//		for (Record.ColumnValue cv : rrec.getColumnValueList()) {
//			Column targetColumn = v.getColumnByName(cv.getColumn().getName());
//			if (targetColumn == null) {
//				continue;
//			}
//			Object targetValue;
//			try {
//				targetValue = convertValueToTargetDBValue(recordMap, v,
//						cv.getColumn(), targetColumn, cv.getValue());
//			} catch (UserDefinedHandlerException ex) {
//				targetValue = cv.getValue();
//				eventHandler.handleEvent(new SingleRecordErrorEvent(rrec, ex));
//			}
//			trec.addColumnValue(targetColumn, targetValue);
//		}
		ArrayList<Column> columnList = (ArrayList<Column>) v.getColumnList();
		ArrayList<ColumnValue> recordList = (ArrayList<ColumnValue>) rrec.getColumnValueList();
		ArrayList<String> colNameList = new ArrayList<String>();
		
		Record newRec = new Record();
		
		for (Column col : columnList) {
			if (col.isSelected()) {
				colNameList.add(col.getName());
			}
		}
		
		for (ColumnValue colVal : recordList) {
			if (colNameList.contains(colVal.getColumn().getName())) {
				newRec.addColumnValue(colVal);
			}
		}
		
		return newRec;
	}
	
	/**
	 * Create a target record by source record
	 * 
	 * @param v Vertex
	 * @param rrec source record
	 * @return Target record
	 */

	public void executeDDL(String sql) {
	}

	public void createFK(FK fk) {
	}

	public void createIndex(Index index) {
	}

	public void createPK(PK pk) {
	}

	public void createSequence(Sequence sq) {
	}

	public void createTable(Table table) {
	}

	public void createView(View view) {
	}

	public int importRecords(SourceTableConfig stc, List<Record> records) {
		return 0;
	}
	
	public Object convertValueToTargetDBValue(Vertex v, Column srcColumn, Column toColumn, Object srcValue) {
		if (srcValue == null) {
			return null;
		}
		
		Object result = null;
		
		return result;
	}

	public int importVertexHeader(Vertex v) {
		return 0;
	}

	public int importEdgeHeader(Edge e) {
		return 0;
	}

	public int importQuickScript() {
		return 0;
	}

}
