package com.orderreporting;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class DatabaseGuardTest {
    @Test void refusesDevelopmentDatabaseBeforeContextStarts(){
        try(var context=new GenericApplicationContext()){
            context.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13307/order_reporting?connectionTimeZone=Asia/Shanghai"));
            assertThatThrownBy(()->new TestDatabaseGuard().initialize(context)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void acceptsOnlyExplicitIndependentTestDatabase(){
        try(var context=new GenericApplicationContext()){
            context.setEnvironment(new MockEnvironment().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:13307/order_reporting_test?connectionTimeZone=Asia/Shanghai"));
            assertThatCode(()->new TestDatabaseGuard().initialize(context)).doesNotThrowAnyException();
        }
    }
}
