package com.company.loopengine.node.security;

import com.company.loopengine.node.application.NodeHeartbeatService;
import com.company.loopengine.node.application.NodeHeartbeatService.AuthenticatedDevice;
import com.company.loopengine.node.application.NodeHeartbeatService.DeviceAuthenticationException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DeviceCertificateFilter extends OncePerRequestFilter {
    public static final String ATTR_NODE_ID = "authenticatedNodeId";
    public static final String SERVLET_CERT_ATTR = "jakarta.servlet.request.X509Certificate";
    private static final Pattern NODE_PATH = Pattern.compile("^/api/node/v1/nodes/([0-9a-fA-F-]{36})(?:/|$)");

    private final NodeHeartbeatService nodes;

    public DeviceCertificateFilter(NodeHeartbeatService nodes) {
        this.nodes = nodes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/node/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            X509Certificate certificate = readVerifiedCertificate(request);
            AuthenticatedDevice device = nodes.authenticateDevice(certificate);
            UUID pathNodeId = pathNodeId(request.getRequestURI());
            if (pathNodeId != null && !pathNodeId.equals(device.nodeId())) {
                throw new DeviceAuthenticationException("certificate does not match node path");
            }
            request.setAttribute(ATTR_NODE_ID, device.nodeId());
            filterChain.doFilter(request, response);
        } catch (DeviceAuthenticationException ex) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                "{\"title\":\"Device authentication failed\",\"detail\":\""
                    + escape(ex.getMessage())
                    + "\"}");
        }
    }

    private static X509Certificate readVerifiedCertificate(HttpServletRequest request) {
        // Never trust client-supplied certificate headers; only the container-verified attribute.
        Object attribute = request.getAttribute(SERVLET_CERT_ATTR);
        if (attribute instanceof X509Certificate[] chain && chain.length > 0 && chain[0] != null) {
            return chain[0];
        }
        if (attribute instanceof X509Certificate single) {
            return single;
        }
        throw new DeviceAuthenticationException("verified client certificate required");
    }

    private static UUID pathNodeId(String uri) {
        if (uri == null) {
            return null;
        }
        Matcher matcher = NODE_PATH.matcher(uri);
        if (!matcher.find()) {
            return null;
        }
        return UUID.fromString(matcher.group(1));
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
