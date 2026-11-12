package com.example.knowledge;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class RateLimitsTest {
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void windowExpiresAndDifferentUsersAreIndependent(){
        var now=new AtomicLong();Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var limits=new RateLimits(clock);assertTrue(limits.take("alice",1).allowed());assertFalse(limits.take("alice",1).allowed());assertTrue(limits.take("bob",1).allowed());assertEquals(60,limits.take("alice",1).retryAfterSeconds());
        now.set(59999);assertEquals(1,limits.take("alice",1).retryAfterSeconds());now.set(60000);assertTrue(limits.take("alice",1).allowed());
    }
    @Test void loginLimitIgnoresSpoofedForwardedIpAndReturnsRetryAfter() throws Exception {
        var filter=new RateLimitFilter(new RateLimits(),new GuardProperties(1,2,2,4,2,10,35),new ObjectMapper());var passed=new AtomicInteger();
        var first=new MockHttpServletRequest("POST","/api/login");first.setRemoteAddr("192.0.2.1");filter.doFilter(first,new MockHttpServletResponse(),(q,r)->passed.incrementAndGet());
        var second=new MockHttpServletRequest("POST","/api/login");second.setRemoteAddr("192.0.2.1");second.addHeader("X-Forwarded-For","203.0.113.10");var response=new MockHttpServletResponse();filter.doFilter(second,response,(q,r)->passed.incrementAndGet());
        assertEquals(1,passed.get());assertEquals(429,response.getStatus());assertNotNull(response.getHeader("Retry-After"));assertTrue(response.getContentAsString().contains("RATE_LIMITED"));
    }
    @Test void questionsAreBoundToAuthenticatedUserNotRequestParameters() throws Exception {
        var filter=new RateLimitFilter(new RateLimits(),new GuardProperties(10,1,1,4,2,10,35),new ObjectMapper());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("alice","","ROLE_READER"));
        filter.doFilter(new MockHttpServletRequest("POST","/api/chats"),new MockHttpServletResponse(),(q,r)->{});
        var forged=new MockHttpServletRequest("POST","/api/chats");forged.addParameter("username","bob");var denied=new MockHttpServletResponse();filter.doFilter(forged,denied,(q,r)->fail());assertEquals(429,denied.getStatus());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("bob","","ROLE_READER"));
        var allowed=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("POST","/api/chats"),allowed,(q,r)->{});assertEquals(200,allowed.getStatus());
        filter.doFilter(new MockHttpServletRequest("GET","/api/chats"),new MockHttpServletResponse(),(q,r)->{});
    }
}
