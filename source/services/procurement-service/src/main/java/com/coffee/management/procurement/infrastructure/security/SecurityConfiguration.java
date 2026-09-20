package com.coffee.management.procurement.infrastructure.security;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationFilter filter) throws Exception {
        return http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(
                        e -> e.authenticationEntryPoint((q, r, x) -> r.sendError(HttpStatus.UNAUTHORIZED.value())))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}

