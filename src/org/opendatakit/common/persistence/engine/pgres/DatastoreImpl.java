/**
 * Copyright (C) 2010 University of Washington
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.common.persistence.engine.pgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.opendatakit.common.persistence.CommonFieldsBase;
import org.opendatakit.common.persistence.DataField;
import org.opendatakit.common.persistence.Datastore;
import org.opendatakit.common.persistence.DynamicAssociationBase;
import org.opendatakit.common.persistence.DynamicBase;
import org.opendatakit.common.persistence.DynamicDocumentBase;
import org.opendatakit.common.persistence.EntityKey;
import org.opendatakit.common.persistence.PersistConsts;
import org.opendatakit.common.persistence.Query;
import org.opendatakit.common.persistence.StaticAssociationBase;
import org.opendatakit.common.persistence.TaskLock;
import org.opendatakit.common.persistence.DataField.DataType;
import org.opendatakit.common.persistence.Query.FilterOperation;
import org.opendatakit.common.persistence.exception.ODKDatastoreException;
import org.opendatakit.common.persistence.exception.ODKEntityNotFoundException;
import org.opendatakit.common.persistence.exception.ODKEntityPersistException;
import org.opendatakit.common.security.User;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 
 * @author wbrunette@gmail.com
 * @author mitchellsundt@gmail.com
 * 
 */
public class DatastoreImpl implements Datastore {

	/**
	 * Maximum size limit, 0 is considered infinite
	 */
	//private static final int BLOB_MAX_SIZE = 0;

	//private static final Integer DEFAULT_FETCH_LIMIT = 1000;

	public static final String PERSISTENCE_CONTEXT = "mysqlPersistenceContext.xml";

	public static final String DATASOURCE_NAME = "dataSource";

	private static final int MAX_COLUMN_NAME_LEN = 64;
	private static final int MAX_TABLE_NAME_LEN = 60; // reserve 4 char for idx name

	private final JdbcTemplate jdbcTemplate;

	private final String schemaName;

	public DatastoreImpl() throws ODKDatastoreException {
		ApplicationContext context = new ClassPathXmlApplicationContext(
				PERSISTENCE_CONTEXT);
		DataSource dataSource = (DataSource) context.getBean(DATASOURCE_NAME);

		this.jdbcTemplate = new JdbcTemplate(dataSource);
		List<?> databaseNames = jdbcTemplate.queryForList("SELECT DATABASE()",
				String.class);
		this.schemaName = (String) databaseNames.get(0);
	}
	
	public static final String K_CREATE_TABLE = "CREATE TABLE ";
	public static final String K_DROP_TABLE = "DROP TABLE ";
	
	public static final String K_OPEN_PAREN = " ( ";
	public static final String K_CLOSE_PAREN = " ) ";
	public static final String K_SELECT = "SELECT ";
	public static final String K_SELECT_DISTINCT = "SELECT DISTINCT ";
	public static final String K_CS = ", ";
	public static final String K_BQ = "`";
	public static final String K_FROM = " FROM ";
	public static final String K_WHERE = " WHERE ";
	public static final String K_AND = " AND ";
	public static final String K_EQ = " = ";
	public static final String K_BIND_VALUE = "?";
	public static final String K_CREATE_INDEX = "CREATE INDEX ";
	public static final String K_ON = " ON ";
	public static final String K_USING_HASH = " USING HASH ";
	public static final String K_INSERT_INTO = "INSERT INTO ";
	public static final String K_VALUES = " VALUES ";
	public static final String K_UPDATE = "UPDATE ";
	public static final String K_SET = " SET ";
	public static final String K_DELETE_FROM = "DELETE FROM ";

	public static final Long DEFAULT_MAX_STRING_SIZE = 255L;
	public static final Integer DEFAULT_DBL_NUMERIC_SCALE = 10;
	public static final Integer DEFAULT_DBL_NUMERIC_PRECISION = 38;
	public static final Integer DEFAULT_INT_NUMERIC_PRECISION = 10;
	
	private static final class TableDefinition {
		
		public DataField.DataType getDataType() {
			return dataType;
		}

		public void setDataType(DataField.DataType dataType) {
			this.dataType = dataType;
		}

		public String getColumnName() {
			return columnName;
		}

		public boolean isNullable() {
			return isNullable;
		}

		public Long getMaxCharLen() {
			return maxCharLen;
		}

		public Integer getNumericScale() {
			return numericScale;
		}

		public Integer getNumericPrecision() {
			return numericPrecision;
		}

		public static final String COLUMN_NAME = "isc.COLUMN_NAME";
		public static final String TABLE_NAME = "isc.TABLE_NAME";
		public static final String TABLE_SCHEMA = "isc.TABLE_SCHEMA";
		public static final String CHARACTER_MAXIMUM_LENGTH = "isc.CHARACTER_MAXIMUM_LENGTH";
		public static final String NUMERIC_PRECISION = "isc.NUMERIC_PRECISION";
		public static final String NUMERIC_SCALE = "isc.NUMERIC_SCALE";
		public static final String DATA_TYPE = "isc.DATA_TYPE";
		public static final String IS_NULLABLE = "isc.IS_NULLABLE";
		public static final String INFORMATION_SCHEMA_COLUMNS = "information_schema.COLUMNS isc";
		public static final String K_COUNT_ONE = "COUNT(1)";
		
		public static final String TABLE_DEF_QUERY = K_SELECT +
					COLUMN_NAME + K_CS + IS_NULLABLE + K_CS + 
					CHARACTER_MAXIMUM_LENGTH + K_CS + NUMERIC_PRECISION + K_CS +
					NUMERIC_SCALE + K_CS + DATA_TYPE + K_FROM + INFORMATION_SCHEMA_COLUMNS +
					K_WHERE + TABLE_SCHEMA + K_EQ + K_BIND_VALUE + 
					K_AND + TABLE_NAME + K_EQ + K_BIND_VALUE;
		
		public static final String TABLE_EXISTS_QUERY = K_SELECT +
					K_COUNT_ONE + K_FROM + INFORMATION_SCHEMA_COLUMNS + 
					K_WHERE + TABLE_SCHEMA + K_EQ + K_BIND_VALUE +
					K_AND + TABLE_NAME + K_EQ + K_BIND_VALUE;
		
		private static final String YES = "YES";
		private static final String TEXT = "text"; // lower case!
		private static final String CHAR = "char";
		private static final String BLOB = "blob"; 
		private static final String BYTEA = "bytea"; // different in pgres
		private static final String DATE = "date";
		// private static final String DATETIME = "datetime";
		private static final String TIME = "time";
		private static final Long MAX_ROW_SIZE = 65000L; // to allow PK room
		
		private String columnName;
		private boolean isNullable;
		private Long maxCharLen = null;
		private Integer numericScale = null;
		private Integer numericPrecision = null;
		private DataField.DataType dataType;
		
		TableDefinition(ResultSet rs) throws SQLException {
			columnName = rs.getString(COLUMN_NAME);
			String s = rs.getString(IS_NULLABLE);
			isNullable = YES.equalsIgnoreCase(s);
			String type = rs.getString(DATA_TYPE);
			BigDecimal num = rs.getBigDecimal(CHARACTER_MAXIMUM_LENGTH);
			if ( num != null ) {
				maxCharLen = num.longValueExact();
				if ( type.contains(TEXT) || type.contains(CHAR)) {
					if ( maxCharLen <= MAX_ROW_SIZE ) {
						dataType = DataField.DataType.STRING;
					} else {
						dataType = DataField.DataType.LONG_STRING;
					}
				} else if ( type.contains(BLOB) || type.contains(BYTEA) ) {
					dataType = DataField.DataType.BINARY;
				} else {
					throw new IllegalArgumentException("unrecognized data type in schema: " + type);	
				}
			} else {
				// must be date or numeric...
				num = rs.getBigDecimal(NUMERIC_SCALE);
				if ( num == null ) {
					// better be a date...
					if ( type.contains(DATE) || type.contains(TIME) ) {
						dataType = DataField.DataType.DATETIME;
					} else {
						throw new IllegalArgumentException("unrecognized data type in schema: " + type);
					}
				} else {
					// discriminate between decimal and integer by looking at value...
					// We assume that nobody is going crazy with the scale here...
					if ( BigDecimal.ZERO.equals(num) ) {
						dataType = DataField.DataType.INTEGER;
						numericScale = 0;
					} else {
						numericScale = num.intValueExact();
						dataType = DataField.DataType.DECIMAL;
					}
					num = rs.getBigDecimal(NUMERIC_PRECISION);
					numericPrecision = num.intValueExact();
				}
			}
		}
	};
	
	private static RowMapper tableDef = new RowMapper() {
		@Override
		public Object mapRow(ResultSet rs, int rowNum) throws SQLException {
			return new TableDefinition(rs);
		}
	};

	JdbcTemplate getJdbcTemplate() {
		return jdbcTemplate;
	}

	@Override
	public String getDefaultSchemaName() {
		return schemaName;
	}
	
	@Override
	public int getMaxLenColumnName() {
		return MAX_COLUMN_NAME_LEN;
	}

	@Override
	public int getMaxLenTableName() {
		return MAX_TABLE_NAME_LEN;
	}

	private final boolean updateRelation(CommonFieldsBase relation ) {

		String qs = TableDefinition.TABLE_DEF_QUERY;
		List<?> columns = jdbcTemplate.query(qs,
					new Object[] {relation.getSchemaName(), relation.getTableName()}, tableDef);

		if ( columns.size() > 0 ) {
			Map<String, TableDefinition> map = new HashMap<String, TableDefinition> ();
			for ( Object o : columns ) {
				TableDefinition t = (TableDefinition) o;
				map.put(t.getColumnName(), t);
			}
			
			// we may have gotten some results into columns -- go through the fields and 
			// assemble the results... we don't care about additional columns in the map...
			for ( DataField f : relation.getFieldList() ) {
				TableDefinition d = map.get(f.getName());
				if ( d == null ) {
					throw new IllegalStateException("did not find expected column " +
										f.getName() + " in table " + relation.getSchemaName() + "." + relation.getTableName());
				}
				if ( f.getDataType() == DataField.DataType.BOOLEAN &&
				     d.getDataType() == DataField.DataType.STRING ) {
					d.setDataType(DataField.DataType.BOOLEAN);
					// don't care about size...
				}
				
				if ( f.getDataType() == DataField.DataType.URI &&
					 d.getDataType() == DataField.DataType.STRING ) {
					d.setDataType(DataField.DataType.URI);
					if ( f.getMaxCharLen() != null && d.getMaxCharLen() < f.getMaxCharLen() ) {
						throw new IllegalStateException("column " + f.getName() +
								" in table " + relation.getSchemaName() + "." + relation.getTableName() + 
								" stores string-valued keys but is shorter than required by Aggregate " +
								d.getMaxCharLen().toString() + " < " + f.getMaxCharLen().toString());
					}
				}
				if ( d.getDataType() != f.getDataType() ) {
					throw new IllegalStateException("column " +
							f.getName() + " in table " + relation.getSchemaName() + "." + relation.getTableName() +
							" is not of the expected type " + f.getDataType().toString());
				}

				// it is OK for the persistence layer to be more lenient with nulls than the data model
				if ( d.isNullable() && ! f.getNullable() ) {
					throw new IllegalStateException("column " +
							f.getName() + " in table " + relation.getSchemaName() + "." + relation.getTableName() +
							" is defined as NOT NULL but the data model requires NULL");
				}
				f.setMaxCharLen(d.getMaxCharLen());
				f.setNumericPrecision(d.getNumericPrecision());
				f.setNumericScale(d.getNumericScale());
			}
			return true;
		} else {
			return false;
		}
	}
	
	/**
	 * Relation manipulation APIs
	 */
	public void createRelation(CommonFieldsBase relation, User user ) throws ODKDatastoreException {

		// see if relation already is defined and update it with dimensions...
		if ( updateRelation( relation ) ) {
			// it exists -- we're done!
			return;
		} else {
			// need to create the table...
			StringBuilder b = new StringBuilder();
			b.append(K_CREATE_TABLE );
			b.append(K_BQ);
			b.append(relation.getSchemaName());
			b.append(K_BQ);
			b.append(".");
			b.append(K_BQ);
			b.append(relation.getTableName());
			b.append(K_BQ);
			b.append(K_OPEN_PAREN);
			boolean firstTime = true;
			for ( DataField f : relation.getFieldList() ) {
				if ( !firstTime ) {
					b.append(K_CS);
				}
				firstTime = false;
				b.append(K_BQ);
				b.append(f.getName());
				b.append(K_BQ);
				DataField.DataType type = f.getDataType();
				switch ( type ) {
				case BINARY:
					b.append(" BYTEA");
					break;
				case LONG_STRING:
					b.append(" TEXT");// b.append(" CHARACTER SET utf8");
					break;
				case STRING:
					b.append(" VARCHAR(");
					Long len = f.getMaxCharLen();
					if ( len == null ) {
						len = DEFAULT_MAX_STRING_SIZE;
					}
					b.append(len.toString());
					b.append(K_CLOSE_PAREN);
					// b.append(" CHARACTER SET utf8");
					break;
				case BOOLEAN:
					b.append(" CHAR(1)");// b.append(" CHARACTER SET utf8");
					break;
				case INTEGER:
					Integer int_digits = f.getNumericPrecision();
					if ( int_digits == null ) {
						int_digits = DEFAULT_INT_NUMERIC_PRECISION;
					}
	
					if ( int_digits > 9 ) {
						b.append(" BIGINT(");
						b.append(int_digits.toString());
						b.append(K_CLOSE_PAREN);
					} else {
						b.append(" INTEGER(");
						b.append(int_digits.toString());
						b.append(K_CLOSE_PAREN);
					}
					break;
				case DECIMAL:
					Integer dbl_digits = f.getNumericPrecision();
					Integer dbl_fract = f.getNumericScale();
					if ( dbl_digits == null ) {
						dbl_digits = DEFAULT_DBL_NUMERIC_PRECISION;
					}
					if ( dbl_fract == null ) {
						dbl_fract = DEFAULT_DBL_NUMERIC_SCALE;
					}
					b.append(" DECIMAL(");
					b.append(dbl_digits.toString());
					b.append(K_CS);
					b.append(dbl_fract.toString());
					b.append(K_CLOSE_PAREN);
					break;
				case DATETIME:
					b.append(" TIMESTAMP WITHOUT TIME ZONE");
					break;
				case URI:
					b.append(" VARCHAR(");
					len = f.getMaxCharLen();
					if ( len == null ) {
						len = PersistConsts.URI_STRING_LEN;
					}
					b.append(len.toString());
					b.append(")");// b.append(" CHARACTER SET utf8");
					break;
				}
				
				if ( f.getNullable()) {
					b.append(" NULL ");
				} else {
					b.append(" NOT NULL ");
				}
			}
			b.append(K_CLOSE_PAREN);
	
			try {
				jdbcTemplate.execute(b.toString());
			} catch ( DataAccessException e ) {
				e.printStackTrace();
				throw new IllegalStateException("unable to execute: " + b.toString() +
						" exception: " + e.getMessage() );
			}
			
			String idx;
			idx = "PK_" + relation.getTableName();
			createIndex(relation, idx, relation.getPrimaryKey(), true);
			idx = "LUD_" + relation.getTableName();
			createIndex(relation, idx, relation.lastUpdateDate, false);
			switch ( relation.getTableType() ) {
			case STATIC:
			case DYNAMIC_DOCUMENT:
				// no index
				break;
			case DYNAMIC:
				// index by parent
				idx = "FKP_" + relation.getTableName();
				createIndex(relation, idx, ((DynamicBase) relation).parentAuri, false);
				break;
			case STATIC_ASSOCIATION:
				// index by dominant type
				idx = "FKD_" + relation.getTableName().substring(0,60);
				createIndex(relation, idx, ((StaticAssociationBase) relation).domAuri, false);
				break;
			case DYNAMIC_ASSOCIATION:
				// index by dominant type
				idx = "FKD_" + relation.getTableName().substring(0,60);
				createIndex(relation, idx, ((DynamicAssociationBase) relation).domAuri, false);
				break;
			}
			
			// and update the relation with actual dimensions...
			updateRelation(relation);
		}
	}
	
	private void createIndex(CommonFieldsBase tbl, String idxName, DataField field, boolean isUnique) {
		StringBuilder b = new StringBuilder();
		
		b.append(K_CREATE_INDEX);
		b.append(idxName);
		b.append(K_ON);
		b.append(K_BQ);
		b.append(tbl.getSchemaName());
		b.append(".");
		b.append(tbl.getTableName());
		b.append(K_BQ);
		if ( field.getDataType() == DataType.URI ) {
			b.append(K_USING_HASH);
		}
		b.append(K_BQ);
		b.append(field.getName());
		b.append(K_BQ);
		
		try {
			jdbcTemplate.execute(b.toString());
		} catch ( DataAccessException e ) {
			e.printStackTrace();
			throw new IllegalStateException("unable to execute: " + b.toString() +
					" exception: " + e.getMessage() );
		}
	}

	@Override
	public boolean hasRelation(String schema, String tableName, User user) {
		String qs = TableDefinition.TABLE_EXISTS_QUERY;
		int columnCount = jdbcTemplate.queryForInt(qs, new Object[] {schema, tableName});
		return (columnCount != 0);
	}

	@Override
	public void deleteRelation(CommonFieldsBase relation, User user)
			throws ODKDatastoreException {
		StringBuilder b = new StringBuilder();
		b.append(K_DROP_TABLE);
		b.append(K_BQ);
		b.append(relation.getSchemaName());
		b.append(".");
		b.append(relation.getTableName());
		b.append(K_BQ);
		
		jdbcTemplate.execute(b.toString());
	}

	/***************************************************************************
	 * Entity manipulation APIs
	 * 
	 */

	@SuppressWarnings("unchecked")
	@Override
	public <T extends CommonFieldsBase> T createEntityUsingRelation(T relation,
			EntityKey topLevelAuriKey, User user) {
		
		// we are generating our own PK, so we don't need to interact with DB yet...
		T row;
		try {
			row = (T) relation.getEmptyRow(user);
		} catch ( Exception e ) {
			throw new IllegalStateException("failed to create empty row", e);
		}

		if ( topLevelAuriKey != null ) {
			if ( row instanceof DynamicAssociationBase ) {
				((DynamicAssociationBase) row).setTopLevelAuri(topLevelAuriKey.getKey());
			} else if ( row instanceof DynamicDocumentBase ) {
				((DynamicDocumentBase) row).setTopLevelAuri(topLevelAuriKey.getKey());
			} else if ( row instanceof DynamicBase ) {
				((DynamicBase) row).setTopLevelAuri(topLevelAuriKey.getKey());
			}
		}
		return row;
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T extends CommonFieldsBase> T getEntity(T relation, String uri, User user)
			throws ODKEntityNotFoundException {
		 Query query = new QueryImpl(relation, this, user);
		 query.addFilter(relation.primaryKey, FilterOperation.EQUAL, uri);
		 try {
			List<? extends CommonFieldsBase> results = query.executeQuery(2);
			if ( results == null || results.size() != 1 ) {
				throw new ODKEntityNotFoundException("Unable to retrieve "
						+ relation.getSchemaName() + "." + relation.getTableName() 
						+ " key: " + uri );
			}
			return (T) results.get(0);
		} catch (ODKDatastoreException e) {
			throw new ODKEntityNotFoundException("Unable to retrieve "
					+ relation.getSchemaName() + "." + relation.getTableName() 
					+ " key: " + uri, e );
		}
	}

	@Override
	public Query createQuery(CommonFieldsBase relation, User user) {
		Query query = new QueryImpl(relation, this, user);
		return query;
	}

	@Override
	public void putEntity(CommonFieldsBase entity, User user)
			throws ODKEntityPersistException {

		boolean first;
		StringBuilder b = new StringBuilder();
		if ( entity.isFromDatabase() ) {
			// we need to do an update
			entity.setDateField(entity.lastUpdateDate, new Date());
			entity.setStringField(entity.lastUpdateUriUser, user.getUriUser());
			
			b.append(K_UPDATE);
			b.append(K_BQ);
			b.append(entity.getSchemaName());
			b.append(".");
			b.append(entity.getTableName());
			b.append(K_BQ);
			b.append(K_SET);

			int idx = 0;
			Object[] ol = new Object[entity.getFieldList().size()];
			int[] il = new int[entity.getFieldList().size()];
			
			first = true;
			// fields...
			for ( DataField f : entity.getFieldList()) {
				// primary key goes in the where clause...
				if ( f == entity.getPrimaryKey() ) continue;
				if ( !first ) {
					b.append(K_CS);
				}
				first = false;
				b.append(K_BQ);
				b.append(f.getName());
				b.append(K_BQ);
				b.append(K_EQ);
				b.append(K_BIND_VALUE);
				
				switch ( f.getDataType() ) {
				case BOOLEAN:
					ol[idx] = entity.getBooleanField(f);
					il[idx] = java.sql.Types.BOOLEAN;
					break;
				case STRING:
				case URI:
					ol[idx] = entity.getStringField(f);
					il[idx] = java.sql.Types.VARCHAR;
					break;
				case INTEGER:
					ol[idx] = entity.getLongField(f);
					il[idx] = java.sql.Types.BIGINT;
					break;
				case DECIMAL:
					ol[idx] = entity.getNumericField(f);
					il[idx] = java.sql.Types.DECIMAL;
					break;
				case DATETIME:
					ol[idx] = entity.getDateField(f);
					il[idx] = java.sql.Types.DATE;
					break;
				default:
					throw new IllegalStateException("Unexpected data type");
				}
				++idx;
			}
			b.append(K_WHERE);
			b.append(K_BQ);
			b.append(entity.primaryKey.getName());
			b.append(K_BQ);
			b.append(K_EQ);
			b.append(K_BIND_VALUE);
			ol[idx] = entity.getUri();
			il[idx] = java.sql.Types.VARCHAR;
			
			// update...
			jdbcTemplate.update(b.toString(), ol, il);
		} else {
			// not yet in database -- insert
			b.append(K_INSERT_INTO);
			b.append(K_BQ);
			b.append(entity.getSchemaName());
			b.append(".");
			b.append(entity.getTableName());
			b.append(K_BQ);
			first = true;
			b.append(K_OPEN_PAREN);
			// fields...
			for ( DataField f : entity.getFieldList()) {
				if ( !first ) {
					b.append(K_CS);
				}
				first = false;
				b.append(K_BQ);
				b.append(f.getName());
				b.append(K_BQ);
			}
			b.append(K_CLOSE_PAREN);
			b.append(K_VALUES);
			
			int idx = 0;
			Object[] ol = new Object[entity.getFieldList().size()];
			int[] il = new int[entity.getFieldList().size()];
			
			first = true;
			b.append(K_OPEN_PAREN);
			// fields...
			for ( DataField f : entity.getFieldList()) {
				if ( !first ) {
					b.append(K_CS);
				}
				first = false;
				b.append(K_BIND_VALUE);
				
				switch ( f.getDataType() ) {
				case BOOLEAN:
					ol[idx] = entity.getBooleanField(f);
					il[idx] = java.sql.Types.BOOLEAN;
					break;
				case STRING:
				case URI:
					ol[idx] = entity.getStringField(f);
					il[idx] = java.sql.Types.VARCHAR;
					break;
				case INTEGER:
					ol[idx] = entity.getLongField(f);
					il[idx] = java.sql.Types.BIGINT;
					break;
				case DECIMAL:
					ol[idx] = entity.getNumericField(f);
					il[idx] = java.sql.Types.DECIMAL;
					break;
				case DATETIME:
					ol[idx] = entity.getDateField(f);
					il[idx] = java.sql.Types.DATE;
					break;
				default:
					throw new IllegalStateException("Unexpected data type");
				}
				++idx;
			}
			b.append(K_CLOSE_PAREN);
			
			// insert...
			jdbcTemplate.update(b.toString(), ol, il);
			entity.setFromDatabase(true); // now it is in the database...
		}
	}

	@Override
	public void putEntities(Collection<? extends CommonFieldsBase> entityList, User user)
			throws ODKEntityPersistException {
		for ( CommonFieldsBase d : entityList ) {
			putEntity(d, user);
		}
	}

	@Override
	public void deleteEntity(EntityKey key, User user) throws ODKDatastoreException {

		CommonFieldsBase d = key.getRelation();
		
		StringBuilder b = new StringBuilder();
		b.append(K_DELETE_FROM);
		b.append(K_BQ);
		b.append(d.getSchemaName());
		b.append(".");
		b.append(d.getTableName());
		b.append(K_BQ);
		b.append(K_WHERE);
		b.append(K_BQ);
		b.append(d.getPrimaryKey().getName());
		b.append(K_BQ);
		b.append(K_EQ);
		b.append(K_BIND_VALUE);

		// TODO: log the deletion
		jdbcTemplate.update(b.toString(), new Object[] {key.getKey()});
	}

	@Override
	public void deleteEntities(Collection<EntityKey> keys, User user)
			throws ODKDatastoreException {
		
		for ( EntityKey k : keys ) {
			deleteEntity(k, user);
		}
	}

  @Override
  public TaskLock createTaskLock() {
    return new TaskLockImpl();
  }
}