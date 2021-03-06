package org.apereo.cas.support.wsfederation.web.flow;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apereo.cas.CasProtocolConstants;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.AuthenticationResult;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.RegisteredServiceProperty;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.services.UnauthorizedServiceException;
import org.apereo.cas.support.wsfederation.WsFederationConfiguration;
import org.apereo.cas.support.wsfederation.WsFederationHelper;
import org.apereo.cas.support.wsfederation.authentication.principal.WsFederationCredential;
import org.apereo.cas.ticket.AbstractTicketException;
import org.apereo.cas.web.flow.CasWebflowConstants;
import org.apereo.cas.web.support.WebUtils;
import org.opensaml.saml.saml1.core.Assertion;
import org.opensaml.soap.wsfed.RequestedSecurityToken;
import org.springframework.webflow.action.AbstractAction;
import org.springframework.webflow.action.EventFactorySupport;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * This class represents an action in the webflow to retrieve WsFederation information on the callback url which is
 * the webflow url (/login).
 *
 * @author John Gasper
 * @since 4.2.0
 */
@Slf4j
@Getter
@Setter
@RequiredArgsConstructor
public class WsFederationAction extends AbstractAction {
    /**
     * Provider url variable.
     */
    public static final String PROVIDERURL = "WsFederationIdentityProviderUrl";

    private static final String QUERYSTRING = "?wa=wsignin1.0&wtrealm=%s&wctx=%s";

    private static final String WA = "wa";
    private static final String WRESULT = "wresult";
    private static final String WSIGNIN = "wsignin1.0";
    private static final String WCTX = "wctx";

    private final WsFederationHelper wsFederationHelper;
    private final Collection<WsFederationConfiguration> configuration;
    private final CentralAuthenticationService centralAuthenticationService;
    private final AuthenticationSystemSupport authenticationSystemSupport;
    private final ServicesManager servicesManager;

    private final String themeParamName;
    private final String localParamName;

    /**
     * Executes the webflow action.
     *
     * @param context the context
     * @return the event
     */
    @Override
    protected Event doExecute(final RequestContext context) {
        try {
            final HttpServletRequest request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
            final String wa = request.getParameter(WA);
            if (StringUtils.isNotBlank(wa) && wa.equalsIgnoreCase(WSIGNIN)) {
                return handleWsFederationAuthenticationRequest(context);
            }
            final WsFederationConfiguration cfg = this.configuration.stream().filter(WsFederationConfiguration::isAutoRedirect).findFirst().orElse(null);
            if (cfg != null) {
                return routeToLoginRequest(context, cfg);
            }
            prepareLoginViewWithWsFederationClients(context);
        } catch (final Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            throw new UnauthorizedServiceException(UnauthorizedServiceException.CODE_UNAUTHZ_SERVICE, ex.getMessage());
        }
        return new EventFactorySupport().event(this, CasWebflowConstants.TRANSITION_ID_PROCEED);
    }

    private void prepareLoginViewWithWsFederationClients(final RequestContext context) {
        final List<WsFedClient> clients = new ArrayList<>();
        final Service service = (Service) context.getFlowScope().get(CasProtocolConstants.PARAMETER_SERVICE);
        this.configuration.forEach(cfg -> {
            final WsFedClient c = new WsFedClient();
            c.setName(cfg.getName());
            final String rpId = getRelyingPartyIdentifier(service, context, cfg);
            c.setRedirectUrl(getAuthorizationUrl(cfg) + rpId);
            c.setReplyingPartyId(rpId);
            clients.add(c);
        });
        context.getFlowScope().put("wsfedUrls", clients);
    }

    private Event routeToLoginRequest(final RequestContext context, final WsFederationConfiguration config) {
        final HttpServletRequest request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
        final HttpSession session = request.getSession();
        final UUID requestUUID = UUID.randomUUID();
        final Service service = (Service) context.getFlowScope().get(CasProtocolConstants.PARAMETER_SERVICE);
        if (service != null) {
            session.setAttribute(CasProtocolConstants.PARAMETER_SERVICE + "-" + requestUUID.toString(), service);
        }
        saveRequestParameter(request, session, this.themeParamName);
        saveRequestParameter(request, session, this.localParamName);
        saveRequestParameter(request, session, CasProtocolConstants.PARAMETER_METHOD);
        final String url = String.format(getAuthorizationUrl(config), getRelyingPartyIdentifier(service, context, config), requestUUID.toString());
        LOGGER.info("Preparing to redirect to the IdP [{}]", url);
        context.getFlowScope().put(PROVIDERURL, url);
        return error();
    }

    private static String getAuthorizationUrl(final WsFederationConfiguration config) {
        return config.getIdentityProviderUrl() + QUERYSTRING;
    }

    private Event handleWsFederationAuthenticationRequest(final RequestContext context) {
        final HttpServletRequest request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
        final String wResult = request.getParameter(WRESULT);
        LOGGER.debug("Parameter [{}] received: [{}]", WRESULT, wResult);
        if (StringUtils.isBlank(wResult)) {
            LOGGER.error("No [{}] parameter is found", WRESULT);
            return error();
        }
        LOGGER.debug("Attempting to create an assertion from the token parameter");
        final RequestedSecurityToken rsToken = this.wsFederationHelper.getRequestSecurityTokenFromResult(wResult);
        final Pair<Assertion, WsFederationConfiguration> assertion = this.wsFederationHelper.buildAndVerifyAssertion(rsToken, configuration);
        if (assertion == null) {
            LOGGER.error("Could not validate assertion via parsing the token from [{}]", WRESULT);
            return error();
        }
        LOGGER.debug("Attempting to validate the signature on the assertion");
        if (!this.wsFederationHelper.validateSignature(assertion)) {
            final String msg = "WS Requested Security Token is blank or the signature is not valid.";
            LOGGER.error(msg);
            throw new IllegalArgumentException(msg);
        }
        return buildCredentialsFromAssertion(context, assertion);
    }

    private Event buildCredentialsFromAssertion(final RequestContext context, final Pair<Assertion, WsFederationConfiguration> assertion) {
        try {
            final HttpServletRequest request = WebUtils.getHttpServletRequestFromExternalWebflowContext(context);
            final HttpSession session = request.getSession();
            final String wCtx = request.getParameter(WCTX);
            LOGGER.debug("Parameter [{}] received: [{}]", WCTX, wCtx);
            if (StringUtils.isBlank(wCtx)) {
                LOGGER.error("No [{}] parameter is found", WCTX);
                return error();
            }
            final Service service = (Service) session.getAttribute(CasProtocolConstants.PARAMETER_SERVICE + "-" + wCtx);
            LOGGER.debug("Creating credential based on the provided assertion");
            final WsFederationCredential credential = this.wsFederationHelper.createCredentialFromToken(assertion.getKey());
            final WsFederationConfiguration configuration = assertion.getValue();
            final String rpId = getRelyingPartyIdentifier(service, context, configuration);
            if (credential != null && credential.isValid(rpId, configuration.getIdentityProviderIdentifier(), configuration.getTolerance())) {
                LOGGER.debug("Validated assertion for the created credential successfully");
                if (configuration.getAttributeMutator() != null) {
                    LOGGER.debug("Modifying credential attributes based on [{}]", configuration.getAttributeMutator().getClass().getSimpleName());
                    configuration.getAttributeMutator().modifyAttributes(credential.getAttributes());
                }
            } else {
                LOGGER.warn("SAML assertions are blank or no longer valid based on RP identifier [{}] and IdP identifier [{}]", rpId, configuration.getIdentityProviderIdentifier());
                final String url = getAuthorizationUrl(configuration) + rpId;
                context.getFlowScope().put(PROVIDERURL, url);
                LOGGER.warn("Created authentication url [{}] and returning error", url);
                return error();
            }
            context.getFlowScope().put(CasProtocolConstants.PARAMETER_SERVICE, service);
            restoreRequestAttribute(request, session, this.themeParamName);
            restoreRequestAttribute(request, session, this.localParamName);
            restoreRequestAttribute(request, session, CasProtocolConstants.PARAMETER_METHOD);
            LOGGER.debug("Creating final authentication result based on the given credential");
            final AuthenticationResult authenticationResult = this.authenticationSystemSupport.handleAndFinalizeSingleAuthenticationTransaction(service, credential);
            LOGGER.debug("Attempting to create a ticket-granting ticket for the authentication result");
            WebUtils.putTicketGrantingTicketInScopes(context, this.centralAuthenticationService.createTicketGrantingTicket(authenticationResult));
            LOGGER.info("Token validated and new [{}] created: [{}]", credential.getClass().getName(), credential);
            return success();
        } catch (final AbstractTicketException e) {
            LOGGER.error(e.getMessage(), e);
            return error();
        }
    }

    /**
     * Get the relying party id for a service.
     *
     * @param service       the service to get an id for
     * @param context       the context
     * @param configuration the configuration
     * @return relying party id
     */
    private String getRelyingPartyIdentifier(final Service service, final RequestContext context, final WsFederationConfiguration configuration) {
        String relyingPartyIdentifier = configuration.getRelyingPartyIdentifier();
        if (service != null) {
            final RegisteredService registeredService = this.servicesManager.findServiceBy(service);
            RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(service, registeredService);
            if (RegisteredServiceProperty.RegisteredServiceProperties.WSFED_RELYING_PARTY_ID.isAssignedTo(registeredService)) {
                relyingPartyIdentifier = RegisteredServiceProperty.RegisteredServiceProperties.WSFED_RELYING_PARTY_ID.getPropertyValue(registeredService).getValue();
            }
        }
        LOGGER.debug("Determined relying party identifier for [{}] to be [{}]", service, relyingPartyIdentifier);
        return relyingPartyIdentifier;
    }

    /**
     * Restore an attribute in web session as an attribute in request.
     *
     * @param request the request
     * @param session the session
     * @param name    the attribute name
     */
    private static void restoreRequestAttribute(final HttpServletRequest request, final HttpSession session, final String name) {
        final String value = (String) session.getAttribute(name);
        request.setAttribute(name, value);
    }

    /**
     * Save a request parameter in the web session.
     *
     * @param request the request
     * @param session the session
     * @param name    the attribute name
     */
    private static void saveRequestParameter(final HttpServletRequest request, final HttpSession session, final String name) {
        final String value = request.getParameter(name);
        if (value != null) {
            session.setAttribute(name, value);
        }
    }

    /**
     * The Wsfed client passed to the webflow view layer.
     */
    @Getter
    @Setter
    public static class WsFedClient implements Serializable {

        private static final long serialVersionUID = 2733280849157146990L;

        private String redirectUrl;

        private String name;

        private String replyingPartyId;
    }
}
