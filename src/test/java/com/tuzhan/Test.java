package com.tuzhan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class Test {

    public static void main(String[] args) {
        String url = "jdbc:clickhouse://localhost:18123/idfa?ssl=false";
        String user = "idfa";
        String password = "idfa@2026";

        try {
            // 加载驱动（新版本可省略显式注册）
            Class.forName("com.clickhouse.jdbc.Driver");

            // 建立连接
            try (Connection conn = DriverManager.getConnection(url, user, password);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT version()")) {

                if (rs.next()) {
                    System.out.println("ClickHouse Version: " + rs.getString(1));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
