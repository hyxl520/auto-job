package com.example.autojob.skeleton.db.mapper;

import com.example.autojob.skeleton.db.AutoJobSQLException;
import com.example.autojob.skeleton.db.DataSourceHolder;
import com.example.autojob.skeleton.db.TransactionManager;
import com.example.autojob.skeleton.enumerate.DatabaseType;
import com.example.autojob.skeleton.framework.boot.AutoJobApplication;
import com.example.autojob.util.convert.DateUtils;
import com.example.autojob.util.convert.DefaultValueUtil;
import com.example.autojob.util.convert.StringUtils;
import com.example.autojob.util.id.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.dbutils.BaseResultSetHandler;
import org.apache.commons.dbutils.QueryRunner;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 基础Mapper
 *
 * @Author Huang Yongxiang
 * @Date 2022/08/17 16:53
 */
@Slf4j
public abstract class BaseMapper<T> {
    protected DataSourceHolder dataSourceHolder;
    protected final QueryRunner queryRunner;
    private final Class<T> type;

    public BaseMapper(DataSourceHolder dataSourceHolder, Class<T> type) {
        this.dataSourceHolder = dataSourceHolder;
        queryRunner = new QueryRunner(dataSourceHolder.getDataSource());
        this.type = type;
    }

    public BaseMapper(Class<T> type) {
        dataSourceHolder = AutoJobApplication
                .getInstance()
                .getDataSourceHolder();
        queryRunner = new QueryRunner(dataSourceHolder.getDataSource());
        this.type = type;

    }

    public int count() {
        String sql = "select count(id) from " + getTableName() + " where del_flag = 0";
        return conditionalCount(sql);
    }

    public int conditionalCount(String sql, Object... params) {
        Connection connection = getConnection();
        try {
            return queryRunner.query(connection, sql, new BaseResultSetHandler<Integer>() {
                @Override
                protected Integer handle() throws SQLException {
                    if (this.next()) {
                        return this.getInt(1);
                    }
                    return 0;
                }
            }, params);
        } catch (SQLException e) {
            e.printStackTrace();
        } finally {
            closeConnection(connection);
        }
        return 0;
    }

    public T selectById(long id) {
        String condition = " where id = ? and del_flag = 0";
        return queryOne(getSelectExpression() + condition, id);
    }

    public boolean deleteById(long id) {
        String condition = " where id = ?";
        return updateOne(getDeleteExpression() + condition, id) == 1;
    }

    public boolean deleteByIdLogic(long id) {
        String condition = " where id = ? and del_flag = 0";
        return updateOne(getLogicDeleteExpression() + condition, id) == 1;
    }

    /**
     * 查询一条数据，该方法会自动关闭连接
     *
     * @param sql    sql语句
     * @param params 参数
     * @return T
     * @author Huang Yongxiang
     * @date 2022/8/27 9:45
     */
    public T queryOne(String sql, Object... params) {
        Connection connection = getConnection();
        try {
            return printExecuteLogs(sql, queryRunner.query(connection, sql, new HumpBeanResultHandler<>(type), params), params);
        } catch (Exception e) {
            e.printStackTrace();
            throw new AutoJobSQLException(e.getMessage(), e.getCause());
        } finally {
            closeConnection(connection);
        }
    }

    public List<T> queryList(String sql, Object... params) {
        Connection connection = getConnection();
        try {
            return printExecuteLogs(sql, queryRunner.query(connection, sql, new HumpBeanListResultHandler<>(type), params), params);
        } catch (Exception e) {
            e.printStackTrace();
            throw new AutoJobSQLException(e.getMessage(), e.getCause());
        } finally {
            closeConnection(connection);
        }
    }

    public int updateOne(String sql, Object... params) {
        Connection connection = getConnection();
        try {
            return printExecuteLogs(sql, queryRunner.update(connection, sql, params), params);
        } catch (SQLException e) {
            e.printStackTrace();
            throw new AutoJobSQLException(e.getMessage(), e.getCause());
        } finally {
            closeConnection(connection);
        }
    }

    public int updateBatch(String sql, Object[][] params) {
        Connection connection = getConnection();
        try {
            return Arrays
                    .stream(queryRunner.batch(connection, sql, params))
                    .sum();
        } catch (Exception e) {
            e.printStackTrace();
            throw new AutoJobSQLException(e.getMessage(), e.getCause());
        } finally {
            closeConnection(connection);
        }
    }

    public int updateEntity(T entity, String condition, Object... params) {
        if (entity == null || StringUtils.isEmpty(condition)) {
            return 0;
        }
        try {
            StringBuilder sql = new StringBuilder();
            sql
                    .append(getUpdateExpression())
                    .append("set ");
            Field[] fields = entity
                    .getClass()
                    .getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value != null) {
                    sql
                            .append(field.getName())
                            .append(" = ");
                    if (value instanceof String) {
                        sql
                                .append("\"")
                                .append(value)
                                .append("\"");
                    } else if (value instanceof Date) {
                        sql
                                .append("\"")
                                .append(DateUtils.parseDateToStr("yyyy-MM-dd HH:mm:ss", (Date) value))
                                .append("\"");
                    } else {
                        sql.append(value);
                    }
                    sql.append(",");
                }
            }
            sql.deleteCharAt(sql.length() - 1);
            sql
                    .append(" where ")
                    .append(condition);
            return updateOne(sql.toString(), params);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1;
    }

    /**
     * 执行事务
     *
     * @param transactionEntries 要执行事务的SQL操作条目
     * @param exceptions         要回滚的异常，默认发生异常就回滚
     * @return boolean
     * @author Huang Yongxiang
     * @date 2022/8/18 12:23
     */
    @SafeVarargs
    public final boolean doTransaction(TransactionEntry[] transactionEntries, Class<? extends Exception>... exceptions) {
        boolean flag = TransactionManager.openTransaction(dataSourceHolder);
        if (!flag) {
            throw new IllegalStateException("开启事务异常");
        }
        Connection connection = getConnection();
        try {
            for (TransactionEntry entry : transactionEntries) {
                entry.runSql(connection);
            }
            TransactionManager.closeTransaction();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            if (exceptions != null && exceptions.length > 0) {
                for (Class<? extends Exception> ex : exceptions) {
                    if (e.getClass() == ex) {
                        dataSourceHolder.rollbackQuietly(connection);
                        break;
                    }
                }
            } else {
                dataSourceHolder.rollbackQuietly(connection);
            }
        }
        return false;
    }

    public int insertList(List<T> entities) {
        if (entities == null || entities.size() == 0) {
            return 0;
        }
        int length = entities
                .get(0)
                .getClass()
                .getDeclaredFields().length;
        String slot = StringUtils.repeat("?", ",", length);
        String sql = "insert into " + getTableName() + "(" + getAllColumns() + ") values" + " (" + slot + ")";
        Object[][] params = new Object[entities.size()][];
        for (int i = 0; i < entities.size(); i++) {
            params[i] = parseEntityFieldValue(entities.get(i));
        }
        return updateBatch(sql, params);
    }

    protected String getPageSql(int skip, int size) {
        DatabaseType type = DatabaseType.getCurrentDatabaseType();
        if (type == null || type == DatabaseType.MY_SQL) {
            return String.format("limit %d, %d", skip, size);
        } else if (type == DatabaseType.POSTGRES_SQL) {
            return String.format("limit %d offset %d", size, skip);
        }
        return String.format("limit %d,%d", skip, size);
    }

    public List<T> page(int pageNum, int size) {
        int skip = (pageNum - 1) * size;
        String condition = String.format(" where del_flag = 0 %s", getPageSql(skip, size));
        return queryList(getSelectExpression() + condition);
    }

    /**
     * 实现对SQL执行的日志打印
     *
     * @param sql    执行的sql语句
     * @param result 结果
     * @param params 参数
     * @return T
     * @author Huang Yongxiang
     * @date 2022/8/26 9:53
     */
    protected static <T> T printExecuteLogs(String sql, T result, Object... params) {
        if (StringUtils.isEmpty(sql)) {
            log.error("empty Sql");
        } else {
            log.debug("Sql ===============> {}", sql);
            if (params != null) {
                StringBuilder stringBuilder = new StringBuilder();
                Arrays
                        .stream(params)
                        .forEach(param -> {
                            if (param == null) {
                                stringBuilder.append("null");
                            } else {
                                stringBuilder
                                        .append(param)
                                        .append("(")
                                        .append(param
                                                .getClass()
                                                .getSimpleName())
                                        .append("),");
                            }
                        });
                log.debug("Params ===============> {}", stringBuilder.length() > 0 ? stringBuilder
                        .deleteCharAt(stringBuilder.length() - 1)
                        .toString() : "");
                if (result instanceof Collection) {
                    log.debug("Total ===============> {}", ((Collection<?>) result).size());
                } else {
                    log.debug("Result ===============> {}", result);
                }
            }

        }
        return result;
    }

    /**
     * 将实体对象的值转为Object数组以供sql注入
     *
     * @param entity 要转化的实体对象
     * @return java.lang.Object[]
     * @author Huang Yongxiang
     * @date 2022/8/17 21:32
     */
    protected static Object[] parseEntityFieldValue(Object entity) {
        if (entity == null) {
            return new Object[]{};
        }
        Field[] fields = entity
                .getClass()
                .getDeclaredFields();
        Object[] entityValues = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i].setAccessible(true);
            try {
                entityValues[i] = fields[i].get(entity);
                if ("delFlag".equals(fields[i].getName()) && entityValues[i] == null) {
                    entityValues[i] = 0;
                }
                if ("id".equals(fields[i].getName()) && entityValues[i] == null) {
                    entityValues[i] = IdGenerator.getNextIdAsLong();
                }
            } catch (IllegalAccessException e) {
                e.printStackTrace();
                return new Object[]{};
            }
        }
        return entityValues;
    }

    /**
     * 生成类似111,222,333的字符串，用于列表查询
     *
     * @param ids id
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/8/25 18:22
     */
    protected String idRepeat(List<Long> ids) {
        if (ids == null || ids.size() == 0) {
            return "";
        }
        StringBuilder idString = new StringBuilder();
        for (Long id : ids) {
            idString
                    .append(id)
                    .append(",");
        }
        return idString
                .deleteCharAt(idString.length() - 1)
                .toString();
    }

    /**
     * 对实体进行值重复，如：open:[ close:] valueOpen:( value:close:) valueSeparator:_ separator:, 可以得到如下示列值
     * <p>
     * [(12_1_3_true),(14_3.2_false)]
     * </p>
     *
     * @param entities       要重复的实体
     * @param open           初始字符
     * @param close          结束字符
     * @param valueOpen      实体值初始字符
     * @param valueClose     实体值结束字符
     * @param valueSeparator 各个实体属性值之间分隔符
     * @param separator      整个实体值分隔符
     * @param ignoreField    要忽略的字段
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/8/18 10:45
     */
    protected static String entityRepeat(List<Object> entities, String open, String close, String valueOpen, String valueClose, String valueSeparator, String separator, String... ignoreField) {
        if (entities == null) {
            throw new NullPointerException();
        }
        if (entities.size() == 0) {
            return null;
        }
        Field[] fields = entities
                .get(0)
                .getClass()
                .getDeclaredFields();
        StringBuilder result = new StringBuilder(DefaultValueUtil.defaultStringWhenEmpty(open, ""));
        for (Object entity : entities) {
            result.append(DefaultValueUtil.defaultStringWhenEmpty(valueOpen, ""));
            for (Field field : fields) {
                field.setAccessible(true);
                if (ignoreField != null && Arrays
                        .asList(ignoreField)
                        .contains(field.getName())) {
                    continue;
                }
                try {
                    result
                            .append(field.get(entity))
                            .append(DefaultValueUtil.defaultStringWhenEmpty(valueSeparator, ","));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            result.deleteCharAt(result.length() - 1);
            result
                    .append(DefaultValueUtil.defaultStringWhenEmpty(valueClose, ""))
                    .append(DefaultValueUtil.defaultStringWhenEmpty(separator, ","));
        }
        return result
                .deleteCharAt(result.length() - 1)
                .append(DefaultValueUtil.defaultStringWhenEmpty(close, ""))
                .toString();

    }


    /**
     * 获取当前mapper关联table的所有数据列
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/8/26 9:52
     */
    public abstract String getAllColumns();

    /**
     * 获取当前mapper关联的表名
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/8/26 9:52
     */
    public abstract String getTableName();

    /**
     * 获取select语句 select (all_columns) from (table_name)
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/9/9 9:23
     */
    public String getSelectExpression() {
        return "select " + getAllColumns() + " from " + getTableName();
    }

    /**
     * 获取delete语句 delete from (table_name)
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/9/9 9:24
     */
    public String getDeleteExpression() {
        return "delete from " + getTableName() + " ";
    }

    /**
     * 获取逻辑删除语句 update (table_name) set del_flag = 1
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/9/9 9:24
     */
    public String getLogicDeleteExpression() {
        return "update " + getTableName() + " set del_flag = 1 ";
    }

    /**
     * 获取update语句 update (table_name)
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/9/9 9:25
     */
    public String getUpdateExpression() {
        return "update " + getTableName() + " ";
    }

    /**
     * 获取insert语句 insert into (table_name)(all_columns) values
     *
     * @return java.lang.String
     * @author Huang Yongxiang
     * @date 2022/9/9 9:25
     */
    public String getInsertExpression() {
        return "insert into " + getTableName() + "(" + getAllColumns() + ") values ";
    }

    private Connection getConnection() {
        if (TransactionManager.isOpenTransaction()) {
            return TransactionManager.getCurrentConnection();
        }
        return dataSourceHolder.getConnection();
    }

    private void closeConnection(Connection connection) {
        if (!TransactionManager.isOpenTransaction()) {
            dataSourceHolder.release(connection);
        }
    }

}
