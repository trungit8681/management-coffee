package com.coffee.management.loyalty.infrastructure.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtVerifier verifier;

    public JwtAuthenticationFilter(JwtVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer "))
            try {
                var actor = verifier.verify(h.substring(7));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,
                        null, actor.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        chain.doFilter(req, res);
    }
}

