package com.campuspi.account;

import com.campuspi.admin.BusinessFileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Coordinates files and account data without creating an AccountService/file-service cycle. */
@Service
public class AccountProfileService {
    private final AccountService accounts;private final BusinessFileService files;private final JdbcTemplate db;
    public AccountProfileService(AccountService accounts,BusinessFileService files,JdbcTemplate db){this.accounts=accounts;this.files=files;this.db=db;}
    public Object patch(AccountService.Identity actor,JsonNode body){
        AccountService.fields(body,"avatar_ref","personal_bio","address","privacy_settings");if(body.isEmpty())throw new ApiException(400,"至少修改一项资料");
        String avatar=body.has("avatar_ref")?"file://"+files.refId(AccountService.text(body,"avatar_ref",1,500,false)):null;
        ObjectNode remaining=body.deepCopy();remaining.remove("avatar_ref");
        return accounts.memberTransaction(actor,()->{
            if(avatar!=null){files.bindRefs(actor.id(),"AVATAR","AVATAR",Long.toString(actor.id()),List.of(avatar));db.update("UPDATE users SET avatar_url=? WHERE user_id=?",avatar,actor.id());}
            if(!remaining.isEmpty())accounts.patch(actor,remaining);
            return accounts.me(actor);
        });
    }
    public Object decorate(Object value){
        if(value instanceof List<?> list)return list.stream().map(this::decorate).toList();
        if(!(value instanceof Map<?,?> map))return value;
        var out=new LinkedHashMap<String,Object>();map.forEach((key,item)->out.put(key.toString(),decorate(item)));
        if(out.containsKey("avatar_url")){
            Object ref=out.get("avatar_url");out.put("avatar_url",null);
            if(ref instanceof String text&&text.startsWith("file://"))try{
                Object grant=files.access(files.refId(text),null);out.put("avatar_url",((Map<?,?>)grant).get("url"));
            }catch(ApiException ignored){/* Avatar failure must not expose a private reference or hide account data. */}
        }
        return out;
    }
}
