package com.coffee.management.identity.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

        @Bean
        PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder(12);
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
                return http
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(authorize -> authorize
                                                .requestMatchers("/actuator/health/**", "/actuator/info",
                                                                "/.well-known/jwks.json", "/api/v1/auth/login",
                                                                "/api/v1/auth/token/refresh")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                                                (request, response, exception) -> response
                                                                .sendError(HttpStatus.UNAUTHORIZED.value())))
                                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                                .build();
        }
}
