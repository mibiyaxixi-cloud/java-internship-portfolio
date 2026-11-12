package com.example.knowledge;

import org.springframework.stereotype.Component;
import java.time.Clock;
import java.util.*;

/** Single-process fixed window. Key count is bounded; does not trust X-Forwarded-For. */
@Component
public class RateLimits {
    private record Window(long expires,int count){}
    public record Decision(boolean allowed,long retryAfterSeconds){}
    private final Map<String,Window> windows=new HashMap<>();private final Clock clock;
    public RateLimits(){this(Clock.systemUTC());}
    RateLimits(Clock clock){this.clock=clock;}
    public synchronized Decision take(String key,int limit) {
        long now=clock.millis();windows.entrySet().removeIf(e->e.getValue().expires<=now);
        var current=windows.get(key);
        if(current==null){if(windows.size()>=10000)return new Decision(false,60);windows.put(key,new Window(now+60000,1));return new Decision(true,0);}
        if(current.count>=limit)return new Decision(false,Math.max(1,(current.expires-now+999)/1000));
        windows.put(key,new Window(current.expires,current.count+1));return new Decision(true,0);
    }
}
