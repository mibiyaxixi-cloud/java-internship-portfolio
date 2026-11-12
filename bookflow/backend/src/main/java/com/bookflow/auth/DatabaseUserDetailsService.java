package com.bookflow.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserMapper mapper;

    public DatabaseUserDetailsService(UserMapper mapper) { this.mapper = mapper; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount account = mapper.findByUsername(username);
        if (account == null) throw new UsernameNotFoundException("Invalid credentials");
        return new BookFlowUser(account);
    }
}
