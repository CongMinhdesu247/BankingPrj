package com.minh.banking.config;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class TimeConfig {
    @Bean Clock bankingClock() { return Clock.systemUTC(); }
}
