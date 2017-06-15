package camp.xit.identity.services.google.impl;

import camp.xit.identity.services.config.AppConfiguration;
import camp.xit.identity.services.google.GSuiteDirectoryService;
import camp.xit.identity.services.google.model.GroupMembership;
import camp.xit.identity.services.google.NoPrivateKeyException;
import camp.xit.identity.services.google.model.GSuiteGroup;
import camp.xit.identity.services.google.model.GroupList;
import camp.xit.identity.services.config.Configuration;
import camp.xit.identity.services.google.InvalidPasswordException;
import camp.xit.identity.services.google.model.*;
import java.security.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.cxf.interceptor.LoggingInInterceptor;
import org.apache.cxf.jaxrs.client.WebClient;
import org.apache.cxf.rs.security.jose.common.JoseType;
import org.apache.cxf.rs.security.jose.jwa.SignatureAlgorithm;
import org.apache.cxf.rs.security.jose.jws.JwsHeaders;
import org.apache.cxf.rs.security.jose.jws.JwsJwtCompactProducer;
import org.apache.cxf.rs.security.jose.jwt.JwtClaims;
import org.apache.cxf.rs.security.jose.jwt.JwtToken;
import org.apache.cxf.rs.security.oauth2.client.AccessTokenGrantWriter;
import org.apache.cxf.rs.security.oauth2.common.ClientAccessToken;
import org.apache.cxf.rs.security.oauth2.grants.jwt.JwtBearerGrant;
import org.apache.cxf.rs.security.oauth2.provider.OAuthJSONProvider;
import org.apache.cxf.rs.security.oauth2.utils.OAuthUtils;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GSuiteDirectoryServiceImpl implements GSuiteDirectoryService, EventHandler {

    private static final Logger log = LoggerFactory.getLogger(GSuiteDirectoryServiceImpl.class);
    private PrivateKey privateKey;
    private Cache<Boolean, ClientAccessToken> tokenCache;
    private Cache<Boolean, Map<GSuiteGroup, GroupMembership>> membershipCache;

    private final WebClient directoryApiClient;
    private final AppConfiguration config;


    public GSuiteDirectoryServiceImpl(Configuration config, WebClient directoryApiClient) {
        this.config = config;
        this.directoryApiClient = directoryApiClient;
        configure();
    }


    private void configure() {
        log.info("Configuring GSuiteDirectoryService ...");
        long tokenLifetime = config.getServiceAccountTokenLifetime();
        log.info("Token lifetime set to {}", tokenLifetime);
        this.tokenCache = Cache2kBuilder.of(Boolean.class, ClientAccessToken.class)
                .expireAfterWrite(tokenLifetime - 3, TimeUnit.SECONDS)
                .loader((key) -> {
                    return key.equals(Boolean.TRUE) ? getAccessToken() : null;
                }).build();
        this.membershipCache = new Cache2kBuilder<Boolean, Map<GSuiteGroup, GroupMembership>>() {
        }
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .loader((key) -> {
                    return key.equals(Boolean.TRUE) ? getAllGroupMembershipInternal() : null;
                }).build();
        try {
            privateKey = config.getServiceAccountKey();
            log.info("Service account private key loaded");
        } catch (NoPrivateKeyException e) {
            log.error(e.getMessage(), e.getCause());
        }
    }


    @Override
    public GroupMembership getGroupMembers(String groupKey) {
        return readGroupMembers(groupKey, null);
    }


    private GroupMembership readGroupMembers(String groupKey, GroupMembership parent) {
        String path = MessageFormat.format("groups/{0}/members", new Object[]{groupKey});

        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path(path);
        ClientAccessToken accessToken = tokenCache.get(true);
        webClient.authorization(accessToken);
        GroupMembership result;
        if (parent != null && parent.getNextPageToken() != null) {
            result = webClient.query("pageToken", parent.getNextPageToken()).get(GroupMembership.class);
            result.getMembers().addAll(parent.getMembers());
        } else {
            result = webClient.get(GroupMembership.class);
        }
        return result.getNextPageToken() != null ? readGroupMembers(groupKey, result) : result;
    }


    @Override
    public GSuiteGroup getGroup(String groupKey) {
        String path = MessageFormat.format("groups/{0}", new Object[]{groupKey});

        WebClient webClient = WebClient.fromClient(directoryApiClient, true);

        ClientAccessToken accessToken = tokenCache.get(true);
        webClient.authorization(accessToken);
        GSuiteGroup group = webClient.path(path).get(GSuiteGroup.class);
        return group;
    }


    @Override
    public GroupList getGroups(String userKey) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("groups");

        webClient.authorization(tokenCache.get(true));
        if (userKey != null) {
            webClient.query("userKey", userKey);
        }
        GroupList groupList = webClient.query("domain", config.getGSuiteDomain()).get(GroupList.class);
        return groupList;
    }


    @Override
    public GroupList getAllGroups() {
        return getGroups(null);
    }


    @Override
    public Map<GSuiteGroup, GroupMembership> getAllGroupMembership() {
        return membershipCache.get(Boolean.TRUE);
    }


    @Override
    public GSuiteUsers getAllUsers() {
        return readAllUsers(null);
    }


    @Override
    public GSuiteUser getUser(String userKey) {
        String path = MessageFormat.format("users/{0}", new Object[]{userKey});
        WebClient webClient = WebClient.fromClient(directoryApiClient, true);

        ClientAccessToken accessToken = tokenCache.get(true);
        webClient.authorization(accessToken);
        GSuiteUser user = webClient.path(path).get(GSuiteUser.class);
        return user;
    }


    @Override
    public void updateUserPassword(String userKey, String password) throws InvalidPasswordException {
        String path = MessageFormat.format("users/{0}", new Object[]{userKey});
        WebClient webClient = WebClient.fromClient(directoryApiClient, true);

        ClientAccessToken accessToken = tokenCache.get(true);
        webClient.authorization(accessToken);
        GSuiteUser user = new GSuiteUser();
        user.setPassword(password);
        Response response = webClient.path(path).put(user);
        if (response.getStatus() != Response.Status.OK.getStatusCode()) {
            throw new InvalidPasswordException("Can't change password. Response: " + response.readEntity(String.class));
        }
    }


    private GSuiteUsers readAllUsers(GSuiteUsers parent) {
        WebClient webClient = WebClient.fromClient(directoryApiClient, true).path("users");
        ClientAccessToken accessToken = tokenCache.get(true);
        webClient.authorization(accessToken);
        GSuiteUsers result;
        webClient.query("domain", config.getGSuiteDomain());
        if (parent != null && parent.getNextPageToken() != null) {
            result = webClient.query("pageToken", parent.getNextPageToken()).get(GSuiteUsers.class);
            result.getUsers().addAll(parent.getUsers());
        } else {
            result = webClient.get(GSuiteUsers.class);
        }
        return result.getNextPageToken() != null ? readAllUsers(result) : result;
    }


    private ClientAccessToken getAccessToken() {
        JwsHeaders headers = new JwsHeaders(JoseType.JWT, SignatureAlgorithm.RS256);
        JwtClaims claims = new JwtClaims();
        claims.setIssuer(config.getServiceAccountClientId());
        claims.setAudience("https://accounts.google.com/o/oauth2/token");
        claims.setSubject(config.getServiceAccountSubject());

        long issuedAt = OAuthUtils.getIssuedAt();
        long tokenTimeout = config.getServiceAccountTokenLifetime();
        claims.setIssuedAt(issuedAt);
        claims.setExpiryTime(issuedAt + tokenTimeout);
        claims.setProperty("scope", "https://www.googleapis.com/auth/admin.directory.group.readonly https://www.googleapis.com/auth/admin.directory.user");

        JwtToken token = new JwtToken(headers, claims);
        JwsJwtCompactProducer p = new JwsJwtCompactProducer(token);
        String base64UrlAssertion = p.signWith(privateKey);

        JwtBearerGrant grant = new JwtBearerGrant(base64UrlAssertion);

        WebClient accessTokenService = WebClient.create("https://accounts.google.com/o/oauth2/token",
                Arrays.asList(new OAuthJSONProvider(), new AccessTokenGrantWriter()));
        WebClient.getConfig(accessTokenService).getInInterceptors().add(new LoggingInInterceptor());

        accessTokenService.type(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON);

        return accessTokenService.post(grant, ClientAccessToken.class);
    }


    private Map<GSuiteGroup, GroupMembership> getAllGroupMembershipInternal() {
        GroupList groups = getAllGroups();
        Map<GSuiteGroup, GroupMembership> result = new HashMap<>();
        groups.getGroups().forEach((group) -> {
            result.put(group, getGroupMembers(group.getId()));
        });
        return result;
    }


    @Override
    public void handleEvent(Event event) {
        if (Configuration.TOPIC_CHANGE.equals(event.getTopic())) {
            configure();
        }
    }
}
