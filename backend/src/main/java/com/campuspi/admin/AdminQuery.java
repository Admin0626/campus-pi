package com.campuspi.admin;

import com.campuspi.account.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;

/** Inclusive Shanghai calendar dates, exclusive next midnight in UTC. */
public final class AdminQuery {
    private AdminQuery() {}
    public static String dates(String column,String from,String to,List<Object> args) {
        if(!column.matches("[A-Za-z_][A-Za-z0-9_.]*"))throw new IllegalArgumentException("Untrusted query column");
        try {
            LocalDate start=from.isBlank()?null:LocalDate.parse(from),end=to.isBlank()?null:LocalDate.parse(to);
            if(start!=null&&(start.getYear()<1900||start.getYear()>9998)||end!=null&&(end.getYear()<1900||end.getYear()>9998)||start!=null&&end!=null&&start.isAfter(end))throw new DateTimeParseException("Invalid range",from+to,0);
            String sql="";var zone=ZoneId.of("Asia/Shanghai");
            if(start!=null){sql+=" AND "+column+">=?";args.add(Timestamp.from(start.atStartOfDay(zone).toInstant()));}
            if(end!=null){sql+=" AND "+column+"<?";args.add(Timestamp.from(end.plusDays(1).atStartOfDay(zone).toInstant()));}
            return sql;
        }catch(DateTimeException ex){throw new ApiException(400,"日期区间无效，请使用YYYY-MM-DD且起始日期不晚于结束日期");}
    }
    public static String keyword(String value) {
        if(value.codePointCount(0,value.length())>100)throw new ApiException(400,"搜索关键词过长");
        return value.trim();
    }
}
