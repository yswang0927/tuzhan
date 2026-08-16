package com.tuzhan.repository;

import com.gdk.jdbc.DBFactory;
import com.gdk.jdbc.JdbcHandler;

public abstract class BaseRepository {
    // 本地业务库
    protected static final JdbcHandler LOCAL_JDBC = DBFactory.create("local");

    // clickhouse数据库
    protected static final JdbcHandler IDFA_JDBC = DBFactory.create("idfa");

}
