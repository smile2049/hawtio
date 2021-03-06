package io.hawt.web;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.security.auth.Subject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import io.hawt.system.ConfigManager;
import io.hawt.system.Helpers;
import org.jolokia.converter.Converters;
import org.jolokia.converter.json.JsonConvertOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class LoginServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final transient Logger LOG = LoggerFactory.getLogger(LoginServlet.class);
    private static final int DEFAULT_SESSION_TIMEOUT = 1800;
    public static final String KNOWN_PRINCIPALS[] = {"UserPrincipal", "KeycloakPrincipal", "JAASPrincipal", "SimplePrincipal"};

    protected Converters converters = new Converters();
    protected JsonConvertOptions options = JsonConvertOptions.DEFAULT;
    protected ConfigManager config;
    private Integer timeout = DEFAULT_SESSION_TIMEOUT;
    private List<String> knownPrincipalList;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException {
        knownPrincipalList = Arrays.asList(KNOWN_PRINCIPALS);
        config = (ConfigManager) servletConfig.getServletContext().getAttribute("ConfigManager");
        if (config != null) {
            String s = config.get("sessionTimeout", "" + DEFAULT_SESSION_TIMEOUT);
            if (s != null) {
                try {
                    timeout = Integer.parseInt(s);
                    // timeout of 0 means default timeout
                    if (timeout == 0) {
                        timeout = DEFAULT_SESSION_TIMEOUT;
                    }
                } catch (Exception e) {
                    // ignore and use our own default of 1/2 hour
                    timeout = DEFAULT_SESSION_TIMEOUT;
                }
            }
        }

        LOG.info("hawtio login is using " + (timeout != null ? timeout + " sec." : "default") + " HttpSession timeout");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {

        resp.setContentType("application/json");
        final PrintWriter out = resp.getWriter();

        HttpSession session = req.getSession(false);

        if (session != null) {
            Subject subject = (Subject) session.getAttribute("subject");
            if (subject == null) {
                LOG.warn("No security subject stored in existing session, invalidating");
                session.invalidate();
                Helpers.doForbidden(resp);
                return;
            }
            sendResponse(session, subject, out);
            return;
        }

        Subject subject = null;
        if (System.getProperty("jboss.server.name") != null) {
            // In WildFly / JBoss EAP privileged action is skipped at AuthenticationFilter
            subject = (Subject) req.getAttribute("subject");
        } else {
            AccessControlContext acc = AccessController.getContext();
            subject = Subject.getSubject(acc);
        }

        if (subject == null) {
            Helpers.doForbidden(resp);
            return;
        }

        String username = getUsernameFromSubject(subject, knownPrincipalList);

        session = req.getSession(true);
        session.setAttribute("subject", subject);
        session.setAttribute("user", username);
        session.setAttribute("org.osgi.service.http.authentication.remote.user", username);
        session.setAttribute("org.osgi.service.http.authentication.type", HttpServletRequest.BASIC_AUTH);
        session.setAttribute("loginTime", GregorianCalendar.getInstance().getTimeInMillis());
        if (timeout != null) {
            session.setMaxInactiveInterval(timeout);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Http session timeout for user {} is {} sec.", username, session.getMaxInactiveInterval());
        }

        sendResponse(session, subject, out);
    }


    public static String getUsernameFromSubject(Subject subject, List<String> knownPrincipalList) {
        Set<Principal> principals = subject.getPrincipals();

        String username = null;

        if (principals != null) {
            for (Principal principal : principals) {
                String principalClass = principal.getClass().getSimpleName();
                if (knownPrincipalList.contains(principalClass)) {
                    username = principal.getName();
                    LOG.debug("Authorizing user {}", username);
                }
            }
        }

        return username;
    }


    protected void sendResponse(HttpSession session, Subject subject, PrintWriter out) {

        Map<String, Object> answer = new HashMap<String, Object>();

        List<Object> principals = new ArrayList<Object>();

        for (Principal principal : subject.getPrincipals()) {
            Map<String, String> data = new HashMap<String, String>();
            data.put("type", principal.getClass().getName());
            data.put("name", principal.getName());
            principals.add(data);
        }

        List<Object> credentials = new ArrayList<Object>();
        for (Object credential : subject.getPublicCredentials()) {
            Map<String, Object> data = new HashMap<String, Object>();
            data.put("type", credential.getClass().getName());
            data.put("credential", credential);
        }

        answer.put("principals", principals);
        answer.put("credentials", credentials);

        ServletHelpers.writeObject(converters, options, out, answer);
    }

}
