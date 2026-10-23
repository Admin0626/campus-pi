package com.campuspi.member;
import static org.assertj.core.api.Assertions.*;
import com.campuspi.account.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class MemberValidationTest {
    final ObjectMapper json=new ObjectMapper();
    @ParameterizedTest @ValueSource(strings={"0.00","1.00","9999999999.99"})
    void validMoneyBoundaries(String amount){assertThat(MemberSupport.amount(json.createObjectNode().put("price",amount),"price").toPlainString()).isEqualTo(amount);}
    @ParameterizedTest @ValueSource(strings={"-1.00","01.00","1","1.0","1.000","1e2","NaN","Infinity","10000000000.00"," 1.00","1.00 ",""})
    void rejectsInvalidMoneyEquivalenceClasses(String amount){assertThatThrownBy(()->MemberSupport.amount(json.createObjectNode().put("price",amount),"price")).isInstanceOf(ApiException.class);}
    @ParameterizedTest @ValueSource(ints={0,1,100000})
    void integerBoundaries(int value){assertThat(MemberSupport.integer(json.createObjectNode().put("stock",value),"stock",0,100000)).isEqualTo(value);}
    @ParameterizedTest @ValueSource(strings={"\"1\"","1.5","null","true","-1","100001","2147483648"})
    void integerInvalidClasses(String value)throws Exception{var node=json.createObjectNode();node.set("stock",json.readTree(value));assertThatThrownBy(()->MemberSupport.integer(node,"stock",0,100000)).isInstanceOf(ApiException.class);}
    @Test void textCountsUnicodeCodePointsAndRejectsControls(){assertThat(MemberSupport.text(json.createObjectNode().put("text","😀".repeat(50)),"text",1,50)).hasSize(100);assertThatThrownBy(()->MemberSupport.text(json.createObjectNode().put("text","😀".repeat(51)),"text",1,50)).isInstanceOf(ApiException.class);assertThatThrownBy(()->MemberSupport.text(json.createObjectNode().put("text","a\u0000b"),"text",1,50)).isInstanceOf(ApiException.class);}
    @Test void safeStatesAndNoBodyRules(){MemberSupport.noBody(null);MemberSupport.noBody(" ");assertThatThrownBy(()->MemberSupport.noBody("{}")).isInstanceOf(ApiException.class);MemberSupport.choice("进行中","进行中");assertThatThrownBy(()->MemberSupport.choice("完成","进行中")).isInstanceOf(ApiException.class);}
}
