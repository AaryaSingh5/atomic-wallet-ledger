package com.example.wallet.service;

import com.example.wallet.api.ApiException;
import com.example.wallet.api.Dtos.LoginRequest;
import com.example.wallet.api.Dtos.RegisterRequest;
import com.example.wallet.api.Dtos.RegisterResponse;
import com.example.wallet.api.Dtos.TokenResponse;
import com.example.wallet.domain.Account;
import com.example.wallet.domain.User;
import com.example.wallet.repo.AccountRepository;
import com.example.wallet.repo.UserRepository;
import com.example.wallet.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, AccountRepository accounts,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Creates the user and their (empty) wallet atomically. */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        User user = users.save(new User(request.name().trim(), email,
                passwordEncoder.encode(request.password())));
        Account account = accounts.save(Account.forUser(user.getId()));
        return new RegisterResponse(user.getId(), user.getEmail(), account.getId());
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(normalize(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        return new TokenResponse(jwtService.generate(user.getId()));
    }

    static String normalize(String email) {
        return email.trim().toLowerCase();
    }
}
