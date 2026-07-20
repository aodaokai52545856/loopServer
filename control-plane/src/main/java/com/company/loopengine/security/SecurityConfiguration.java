package com.company.loopengine.security;

import com.company.loopengine.node.security.DeviceCertificateFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "loop.security.enabled", havingValue = "true")
public class SecurityConfiguration {
    private static final Pattern PROJECT_REVISION =
            Pattern.compile("^/api/projects/([^/]+)/revisions/?$");

    @Bean
    @Order(1)
    SecurityFilterChain nodeApi(HttpSecurity http, DeviceCertificateFilter deviceFilter)
            throws Exception {
        // DeviceCertificateFilter authenticates via mTLS into ATTR_NODE_ID; it does not
        // populate SecurityContext, so never require .authenticated() here.
        return http.securityMatcher("/api/node/v1/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/node/v1/enroll")
                        .permitAll()
                        .anyRequest()
                        .access(deviceAuthenticated()))
                .addFilterBefore(deviceFilter, AnonymousAuthenticationFilter.class)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain internalHooks(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/webhooks/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain humanApi(
            HttpSecurity http,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService,
            ProjectAuthorization projectAuthorization)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");
        return http.securityMatcher("/api/**", "/oauth2/**", "/login/**")
                .authorizeHttpRequests(auth -> auth
                        // Real enroll path (architecture): invite-code join, not browser OAuth.
                        .requestMatchers(HttpMethod.POST, "/api/v1/nodes/join")
                        .permitAll()
                        .requestMatchers("/api/session")
                        .authenticated()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/projects/*/revisions")
                        .access(projectEditManager(projectAuthorization))
                        .requestMatchers(HttpMethod.GET, "/api/**")
                        .hasAnyRole("ADMIN", "PROJECT_OWNER", "NODE_OWNER", "OBSERVER")
                        .anyRequest()
                        .authenticated())
                .oauth2Login(oauth2 -> oauth2.userInfoEndpoint(
                        userInfo -> userInfo.userService(oauth2UserService)))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/api/v1/nodes/join"))
                .exceptionHandling(ex ->
                        ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    private static AuthorizationManager<RequestAuthorizationContext> deviceAuthenticated() {
        return (authentication, context) -> new AuthorizationDecision(
                context.getRequest().getAttribute(DeviceCertificateFilter.ATTR_NODE_ID) != null);
    }

    private static AuthorizationManager<RequestAuthorizationContext> projectEditManager(
            ProjectAuthorization projectAuthorization) {
        return (authentication, context) -> {
            Authentication auth = authentication.get();
            if (auth == null) {
                auth = SecurityContextHolder.getContext().getAuthentication();
            }
            HttpServletRequest request = context.getRequest();
            Matcher matcher = PROJECT_REVISION.matcher(request.getRequestURI());
            if (!matcher.matches()) {
                return new AuthorizationDecision(false);
            }
            String projectKey = matcher.group(1);
            return new AuthorizationDecision(projectAuthorization.canEditProject(auth, projectKey));
        };
    }

    @RestController
    static class SessionApi {
        @GetMapping("/api/session")
        ResponseEntity<Map<String, Object>> currentUser(@AuthenticationPrincipal OAuth2User oauthUser) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Map<String, Object> body = new LinkedHashMap<>();
            if (oauthUser != null) {
                body.put("id", oauthUser.getAttribute("id"));
                body.put("username", oauthUser.getAttribute("username"));
                body.put("name", oauthUser.getAttribute("name"));
                body.put("email", oauthUser.getAttribute("email"));
            } else if (authentication != null) {
                body.put("id", authentication.getName());
                body.put("username", authentication.getName());
            }
            if (authentication != null) {
                body.put(
                        "roles",
                        authentication.getAuthorities().stream()
                                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                                .sorted()
                                .collect(Collectors.toList()));
            }
            // Never expose OAuth access/refresh tokens through the session API.
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
        }
    }
}
