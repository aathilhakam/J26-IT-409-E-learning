package lk.smartplay.adaptive.config;

import lk.smartplay.adaptive.model.TeacherAccount;
import lk.smartplay.adaptive.repository.TeacherAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;
import java.util.Map;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService userDetailsService(TeacherAccountRepository repository) {
        return email -> {
            TeacherAccount account = repository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
            return User.withUsername(account.getEmail())
                    .password(account.getPasswordHash())
                    .roles("TEACHER")
                    .build();
        };
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/csrf", "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/api/content/import-grade-one", "/api/content/import-grade-one/**").hasRole("TEACHER")
                        .requestMatchers("/api/dashboard/**", "/api/worksheet-scores/**").hasRole("TEACHER")
                        .requestMatchers("/api/students/*/feedback", "/api/students/*/observations", "/api/students/*/history",
                                "/api/recommendations/student/*/milestones", "/api/recommendations/student/*/pathway", "/api/recommendations/student/*/features",
                                "/api/recommendations/*/assign").hasRole("TEACHER")
                        .anyRequest().authenticated())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            writeError(objectMapper, response, "Please log in to continue.");
                        }));
        return http.build();
    }

    private static void writeError(ObjectMapper objectMapper, jakarta.servlet.http.HttpServletResponse response, String message)
            throws IOException {
        objectMapper.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
