package com.campuspi.admin;

import com.campuspi.account.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-owned grants, never accepted from a form or token claims. Empty by default. */
@Component
public class AdminPolicy {
    private final JsonNode grants;
    public AdminPolicy(ObjectMapper mapper,@Value("${campus.admin.duties-json:}") String config) {
        try {
            grants=config.isBlank()?mapper.createObjectNode():mapper.readTree(config);
            if(!grants.isObject())throw new IllegalArgumentException();
            grants.fields().forEachRemaining(entry->{
                AccountService.id(entry.getKey());
                if(!entry.getValue().isArray())throw new IllegalArgumentException();
                entry.getValue().forEach(duty->{if(!duty.isTextual())throw new IllegalArgumentException();});
            });
        } catch(Exception ex) {throw new IllegalArgumentException("Invalid server-owned admin duty configuration");}
    }
    public void require(AccountService.Identity identity,String duty) {
        if(identity==null || !identity.admin())throw new ApiException(403,"需要管理员权限");
        for(JsonNode value:grants.path(Long.toString(identity.id())))if(duty.equals(value.asText()))return;
        throw new ApiException(403,"该管理职责尚未授权："+duty);
    }
}
