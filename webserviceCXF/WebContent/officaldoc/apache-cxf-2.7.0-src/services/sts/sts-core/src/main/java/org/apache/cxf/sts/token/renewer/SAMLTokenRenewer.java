/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.sts.token.renewer;

import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.ws.handler.MessageContext;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.security.transport.TLSSessionInfo;
import org.apache.cxf.sts.STSConstants;
import org.apache.cxf.sts.STSPropertiesMBean;
import org.apache.cxf.sts.SignatureProperties;
import org.apache.cxf.sts.request.ReceivedToken;
import org.apache.cxf.sts.request.ReceivedToken.STATE;
import org.apache.cxf.sts.token.provider.ConditionsProvider;
import org.apache.cxf.sts.token.provider.DefaultConditionsProvider;
import org.apache.cxf.sts.token.realm.SAMLRealm;
import org.apache.cxf.ws.security.sts.provider.STSException;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.cxf.ws.security.wss4j.policyvalidators.AbstractSamlPolicyValidator;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSDocInfo;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.handler.RequestData;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.apache.ws.security.handler.WSHandlerResult;
import org.apache.ws.security.saml.SAMLKeyInfo;
import org.apache.ws.security.saml.ext.AssertionWrapper;
import org.apache.ws.security.saml.ext.bean.ConditionsBean;
import org.apache.ws.security.saml.ext.builder.SAML1ComponentBuilder;
import org.apache.ws.security.saml.ext.builder.SAML2ComponentBuilder;
import org.apache.ws.security.util.UUIDGenerator;
import org.apache.ws.security.util.WSSecurityUtil;
import org.joda.time.DateTime;
import org.opensaml.common.SAMLVersion;
import org.opensaml.saml1.core.Audience;
import org.opensaml.saml1.core.AudienceRestrictionCondition;
import org.opensaml.saml2.core.AudienceRestriction;

/**
 * A TokenRenewer implementation that renews a (valid or expired) SAML Token.
 */
public class SAMLTokenRenewer implements TokenRenewer {
    
    // The default maximum expired time a token is allowed to be is 30 minutes
    public static final long DEFAULT_MAX_EXPIRY = 60L * 30L;
    
    private static final Logger LOG = LogUtils.getL7dLogger(SAMLTokenRenewer.class);
    private boolean signToken = true;
    private ConditionsProvider conditionsProvider = new DefaultConditionsProvider();
    private Map<String, SAMLRealm> realmMap = new HashMap<String, SAMLRealm>();
    private long maxExpiry = DEFAULT_MAX_EXPIRY;
    // boolean to enable/disable the check of proof of possession
    private boolean verifyProofOfPossession = true;
    private boolean allowRenewalAfterExpiry;
    
    /**
     * Return true if this TokenRenewer implementation is able to renew a token.
     */
    public boolean canHandleToken(ReceivedToken renewTarget) {
        return canHandleToken(renewTarget, null);
    }
    
    /**
     * Return true if this TokenRenewer implementation is able to renew a token in the given realm.
     */
    public boolean canHandleToken(ReceivedToken renewTarget, String realm) {
        if (realm != null && !realmMap.containsKey(realm)) {
            return false;
        }
        Object token = renewTarget.getToken();
        if (token instanceof Element) {
            Element tokenElement = (Element)token;
            String namespace = tokenElement.getNamespaceURI();
            String localname = tokenElement.getLocalName();
            if ((WSConstants.SAML_NS.equals(namespace) || WSConstants.SAML2_NS.equals(namespace))
                && "Assertion".equals(localname)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set whether proof of possession is required or not to renew a token
     */
    public void setVerifyProofOfPossession(boolean verifyProofOfPossession) {
        this.verifyProofOfPossession = verifyProofOfPossession;
    }
    
    /**
     * Get whether we allow renewal after expiry. The default is false.
     */
    public boolean isAllowRenewalAfterExpiry() {
        return allowRenewalAfterExpiry;
    }

    /**
     * Set whether we allow renewal after expiry. The default is false.
     */
    public void setAllowRenewalAfterExpiry(boolean allowRenewalAfterExpiry) {
        this.allowRenewalAfterExpiry = allowRenewalAfterExpiry;
    }
    
    /**
     * Set a new value (in seconds) for how long a token is allowed to be expired for before renewal. 
     * The default is 30 minutes.
     */
    public void setMaxExpiry(long newExpiry) {
        maxExpiry = newExpiry;
    }
    
    /**
     * Get how long a token is allowed to be expired for before renewal (in seconds). The default is 
     * 30 minutes.
     */
    public long getMaxExpiry() {
        return maxExpiry;
    }
    
    /**
     * Renew a token given a TokenRenewerParameters
     */
    public TokenRenewerResponse renewToken(TokenRenewerParameters tokenParameters) {
        TokenRenewerResponse response = new TokenRenewerResponse();
        ReceivedToken tokenToRenew = tokenParameters.getToken();
        if (tokenToRenew == null || tokenToRenew.getToken() == null
            || (tokenToRenew.getState() != STATE.EXPIRED && tokenToRenew.getState() != STATE.VALID)) {
            LOG.log(Level.WARNING, "The token to renew is null or invalid");
            throw new STSException(
                "The token to renew is null or invalid", STSException.INVALID_REQUEST
            );
        }
        
        TokenStore tokenStore = tokenParameters.getTokenStore();
        if (tokenStore == null) {
            LOG.log(Level.FINE, "A cache must be configured to use the SAMLTokenRenewer");
            throw new STSException("Can't renew SAML assertion", STSException.REQUEST_FAILED);
        }
        
        try {
            AssertionWrapper assertion = new AssertionWrapper((Element)tokenToRenew.getToken());
            
            byte[] oldSignature = assertion.getSignatureValue();
            int hash = Arrays.hashCode(oldSignature);
            SecurityToken cachedToken = tokenStore.getToken(Integer.toString(hash));
            if (cachedToken == null) {
                LOG.log(Level.FINE, "The token to be renewed must be stored in the cache");
                throw new STSException("Can't renew SAML assertion", STSException.REQUEST_FAILED);
            }
            
            // Validate the Assertion
            validateAssertion(assertion, tokenToRenew, cachedToken, tokenParameters);
            
            AssertionWrapper renewedAssertion = new AssertionWrapper(assertion.getXmlObject());
            String oldId = createNewId(renewedAssertion);
            // Remove the previous token (now expired) from the cache
            tokenStore.remove(oldId);
            tokenStore.remove(Integer.toString(hash));
            
            // Create new Conditions & sign the Assertion
            createNewConditions(renewedAssertion, tokenParameters);
            signAssertion(renewedAssertion, tokenParameters);
            
            Document doc = DOMUtils.createDocument();
            Element token = renewedAssertion.toDOM(doc);
            if (renewedAssertion.getSaml1() != null) {
                token.setIdAttributeNS(null, "AssertionID", true);
            } else {
                token.setIdAttributeNS(null, "ID", true);
            }
            doc.appendChild(token);
            
            // Cache the token
            storeTokenInCache(
                tokenStore, renewedAssertion, tokenParameters.getPrincipal(), tokenParameters.getRealm()
            );
            
            response.setToken(token);
            response.setTokenId(renewedAssertion.getId());
            
            DateTime validFrom = null;
            DateTime validTill = null;
            long lifetime = 0;
            if (renewedAssertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
                validFrom = renewedAssertion.getSaml2().getConditions().getNotBefore();
                validTill = renewedAssertion.getSaml2().getConditions().getNotOnOrAfter();
                lifetime = validTill.getMillis() - validFrom.getMillis();
            } else {
                validFrom = renewedAssertion.getSaml1().getConditions().getNotBefore();
                validTill = renewedAssertion.getSaml1().getConditions().getNotOnOrAfter();
                lifetime = validTill.getMillis() - validFrom.getMillis();
            }
            response.setLifetime(lifetime / 1000);

            return response;
            
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "", ex);
            throw new STSException("Can't renew SAML assertion", ex, STSException.REQUEST_FAILED);
        }
    }
    
    /**
     * Set the ConditionsProvider
     */
    public void setConditionsProvider(ConditionsProvider conditionsProvider) {
        this.conditionsProvider = conditionsProvider;
    }
    
    /**
     * Get the ConditionsProvider
     */
    public ConditionsProvider getConditionsProvider() {
        return conditionsProvider;
    }

    /**
     * Return whether the provided token will be signed or not. Default is true.
     */
    public boolean isSignToken() {
        return signToken;
    }

    /**
     * Set whether the provided token will be signed or not. Default is true.
     */
    public void setSignToken(boolean signToken) {
        this.signToken = signToken;
    }
    
    /**
     * Set the map of realm->SAMLRealm for this token provider
     * @param realms the map of realm->SAMLRealm for this token provider
     */
    public void setRealmMap(Map<String, SAMLRealm> realms) {
        this.realmMap = realms;
    }
    
    /**
     * Get the map of realm->SAMLRealm for this token provider
     * @return the map of realm->SAMLRealm for this token provider
     */
    public Map<String, SAMLRealm> getRealmMap() {
        return realmMap;
    }
    
    private void validateAssertion(
        AssertionWrapper assertion,
        ReceivedToken tokenToRenew,
        SecurityToken token,
        TokenRenewerParameters tokenParameters
    ) throws WSSecurityException {
        // Check the cached renewal properties
        Properties props = token.getProperties();
        if (props == null) {
            LOG.log(Level.WARNING, "Error in getting properties from cached token");
            throw new STSException(
                "Error in getting properties from cached token", STSException.REQUEST_FAILED
            );
        }
        String isAllowRenewal = (String)props.get(STSConstants.TOKEN_RENEWING_ALLOW);
        String isAllowRenewalAfterExpiry = 
            (String)props.get(STSConstants.TOKEN_RENEWING_ALLOW_AFTER_EXPIRY);
        
        if (isAllowRenewal == null || !Boolean.valueOf(isAllowRenewal)) {
            LOG.log(Level.WARNING, "The token is not allowed to be renewed");
            throw new STSException("The token is not allowed to be renewed", STSException.REQUEST_FAILED);
        }
        
        // Check to see whether the token has expired greater than the configured max expiry time
        if (tokenToRenew.getState() == STATE.EXPIRED) {
            if (!allowRenewalAfterExpiry || isAllowRenewalAfterExpiry == null
                || !Boolean.valueOf(isAllowRenewalAfterExpiry)) {
                LOG.log(Level.WARNING, "Renewal after expiry is not allowed");
                throw new STSException(
                    "Renewal after expiry is not allowed", STSException.REQUEST_FAILED
                );
            }
            DateTime expiryDate = getExpiryDate(assertion);
            DateTime currentDate = new DateTime();
            if ((currentDate.getMillis() - expiryDate.getMillis()) > (maxExpiry * 1000L)) {
                LOG.log(Level.WARNING, "The token expired too long ago to be renewed");
                throw new STSException(
                    "The token expired too long ago to be renewed", STSException.REQUEST_FAILED
                );
            }
        }
        
        // Verify Proof of Possession
        ProofOfPossessionValidator popValidator = new ProofOfPossessionValidator();
        if (verifyProofOfPossession) {
            STSPropertiesMBean stsProperties = tokenParameters.getStsProperties();
            Crypto sigCrypto = stsProperties.getSignatureCrypto();
            CallbackHandler callbackHandler = stsProperties.getCallbackHandler();
            RequestData requestData = new RequestData();
            requestData.setSigCrypto(sigCrypto);
            WSSConfig wssConfig = WSSConfig.getNewInstance();
            requestData.setWssConfig(wssConfig);
            requestData.setCallbackHandler(callbackHandler);
            // Parse the HOK subject if it exists
            assertion.parseHOKSubject(
                requestData, new WSDocInfo(((Element)tokenToRenew.getToken()).getOwnerDocument())
            );
        
            SAMLKeyInfo keyInfo = assertion.getSubjectKeyInfo();
            if (keyInfo == null) {
                keyInfo = new SAMLKeyInfo((byte[])null);
            }
            if (!popValidator.checkProofOfPossession(tokenParameters, keyInfo)) {
                throw new STSException(
                    "Failed to verify the proof of possession of the key associated with the "
                    + "saml token. No matching key found in the request.",
                    STSException.INVALID_REQUEST
                );
            }
        }
        
        // Check the AppliesTo address
        String appliesToAddress = tokenParameters.getAppliesToAddress();
        if (appliesToAddress != null) {
            if (assertion.getSaml1() != null) {
                List<AudienceRestrictionCondition> restrConditions = 
                    assertion.getSaml1().getConditions().getAudienceRestrictionConditions();
                if (!matchSaml1AudienceRestriction(appliesToAddress, restrConditions)) {
                    LOG.log(Level.WARNING, "The AppliesTo address does not match the Audience Restriction");
                    throw new STSException(
                        "The AppliesTo address does not match the Audience Restriction",
                        STSException.INVALID_REQUEST
                    );
                }
            } else {
                List<AudienceRestriction> audienceRestrs = 
                    assertion.getSaml2().getConditions().getAudienceRestrictions();
                if (!matchSaml2AudienceRestriction(appliesToAddress, audienceRestrs)) {
                    LOG.log(Level.WARNING, "The AppliesTo address does not match the Audience Restriction");
                    throw new STSException(
                        "The AppliesTo address does not match the Audience Restriction",
                        STSException.INVALID_REQUEST
                    );
                }
            }
        }
        
    }
    
    private boolean matchSaml1AudienceRestriction(
        String appliesTo, List<AudienceRestrictionCondition> restrConditions
    ) {
        boolean found = false;
        if (restrConditions != null && !restrConditions.isEmpty()) {
            for (AudienceRestrictionCondition restrCondition : restrConditions) {
                if (restrCondition.getAudiences() != null) {
                    for (Audience audience : restrCondition.getAudiences()) {
                        if (appliesTo.equals(audience.getUri())) {
                            return true;
                        }
                    }
                }
            }
        }
        
        return found;
    }
    
    private boolean matchSaml2AudienceRestriction(
        String appliesTo, List<AudienceRestriction> audienceRestrictions
    ) {
        boolean found = false;
        if (audienceRestrictions != null && !audienceRestrictions.isEmpty()) {
            for (AudienceRestriction audienceRestriction : audienceRestrictions) {
                if (audienceRestriction.getAudiences() != null) {
                    for (org.opensaml.saml2.core.Audience audience : audienceRestriction.getAudiences()) {
                        if (appliesTo.equals(audience.getAudienceURI())) {
                            return true;
                        }
                    }
                }
            }
        }

        return found;
    }
    
    private void signAssertion(
        AssertionWrapper assertion,
        TokenRenewerParameters tokenParameters
    ) throws Exception {
        if (signToken) {
            STSPropertiesMBean stsProperties = tokenParameters.getStsProperties();
            
            // Initialise signature objects with defaults of STSPropertiesMBean
            Crypto signatureCrypto = stsProperties.getSignatureCrypto();
            CallbackHandler callbackHandler = stsProperties.getCallbackHandler();
            SignatureProperties signatureProperties = stsProperties.getSignatureProperties();
            String alias = stsProperties.getSignatureUsername();
            
            String realm = tokenParameters.getRealm();
            SAMLRealm samlRealm = null;
            if (realm != null && realmMap.containsKey(realm)) {
                samlRealm = realmMap.get(realm);
            }
            if (samlRealm != null) {
                // If SignatureCrypto configured in realm then
                // callbackhandler and alias of STSPropertiesMBean is ignored
                if (samlRealm.getSignatureCrypto() != null) {
                    LOG.fine("SAMLRealm signature keystore used");
                    signatureCrypto = samlRealm.getSignatureCrypto();
                    callbackHandler = samlRealm.getCallbackHandler();
                    alias = samlRealm.getSignatureAlias();
                }
                // SignatureProperties can be defined independently of SignatureCrypto
                if (samlRealm.getSignatureProperties() != null) {
                    signatureProperties = samlRealm.getSignatureProperties();
                }
            }
            
            // Get the signature algorithm to use
            String signatureAlgorithm = tokenParameters.getKeyRequirements().getSignatureAlgorithm();
            if (signatureAlgorithm == null) {
                // If none then default to what is configured
                signatureAlgorithm = signatureProperties.getSignatureAlgorithm();
            } else {
                List<String> supportedAlgorithms = 
                    signatureProperties.getAcceptedSignatureAlgorithms();
                if (!supportedAlgorithms.contains(signatureAlgorithm)) {
                    signatureAlgorithm = signatureProperties.getSignatureAlgorithm();
                    LOG.fine("SignatureAlgorithm not supported, defaulting to: " + signatureAlgorithm);
                }
            }
            
            // Get the c14n algorithm to use
            String c14nAlgorithm = tokenParameters.getKeyRequirements().getC14nAlgorithm();
            if (c14nAlgorithm == null) {
                // If none then default to what is configured
                c14nAlgorithm = signatureProperties.getC14nAlgorithm();
            } else {
                List<String> supportedAlgorithms = 
                    signatureProperties.getAcceptedC14nAlgorithms();
                if (!supportedAlgorithms.contains(c14nAlgorithm)) {
                    c14nAlgorithm = signatureProperties.getC14nAlgorithm();
                    LOG.fine("C14nAlgorithm not supported, defaulting to: " + c14nAlgorithm);
                }
            }
            
            // If alias not defined, get the default of the SignatureCrypto
            if ((alias == null || "".equals(alias)) && (signatureCrypto != null)) {
                alias = signatureCrypto.getDefaultX509Identifier();
                LOG.fine("Signature alias is null so using default alias: " + alias);
            }
            // Get the password
            WSPasswordCallback[] cb = {new WSPasswordCallback(alias, WSPasswordCallback.SIGNATURE)};
            LOG.fine("Creating SAML Token");
            callbackHandler.handle(cb);
            String password = cb[0].getPassword();
    
            LOG.fine("Signing SAML Token");
            boolean useKeyValue = signatureProperties.isUseKeyValue();
            assertion.signAssertion(
                alias, password, signatureCrypto, useKeyValue, c14nAlgorithm, signatureAlgorithm
            );
        } else {
            if (assertion.getSaml1().getSignature() != null) {
                assertion.getSaml1().setSignature(null);
            } else if (assertion.getSaml2().getSignature() != null) {
                assertion.getSaml2().setSignature(null);
            } 
        }
        
    }
    
    private void createNewConditions(AssertionWrapper assertion, TokenRenewerParameters tokenParameters) {
        ConditionsBean conditions = 
            conditionsProvider.getConditions(
                tokenParameters.getAppliesToAddress(),
                tokenParameters.getTokenRequirements().getLifetime()
            );
        
        if (assertion.getSaml1() != null) {
            org.opensaml.saml1.core.Assertion saml1Assertion = assertion.getSaml1();
            saml1Assertion.setIssueInstant(new DateTime());
            
            org.opensaml.saml1.core.Conditions saml1Conditions =
                SAML1ComponentBuilder.createSamlv1Conditions(conditions);
            
            saml1Assertion.setConditions(saml1Conditions);
        } else {
            org.opensaml.saml2.core.Assertion saml2Assertion = assertion.getSaml2();
            saml2Assertion.setIssueInstant(new DateTime());
            
            org.opensaml.saml2.core.Conditions saml2Conditions =
                SAML2ComponentBuilder.createConditions(conditions);
            
            saml2Assertion.setConditions(saml2Conditions);
        }
    }
    
    private String createNewId(AssertionWrapper assertion) {
        if (assertion.getSaml1() != null) {
            org.opensaml.saml1.core.Assertion saml1Assertion = assertion.getSaml1();
            String oldId = saml1Assertion.getID();
            saml1Assertion.setID("_" + UUIDGenerator.getUUID());
            
            return oldId;
        } else {
            org.opensaml.saml2.core.Assertion saml2Assertion = assertion.getSaml2();
            String oldId = saml2Assertion.getID();
            saml2Assertion.setID("_" + UUIDGenerator.getUUID());
            
            return oldId;
        }
    }
    
    private void storeTokenInCache(
        TokenStore tokenStore, 
        AssertionWrapper assertion, 
        Principal principal,
        String tokenRealm
    ) throws WSSecurityException {
        // Store the successfully renewed token in the cache
        byte[] signatureValue = assertion.getSignatureValue();
        if (tokenStore != null && signatureValue != null && signatureValue.length > 0) {
            DateTime validTill = null;
            if (assertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
                validTill = assertion.getSaml2().getConditions().getNotOnOrAfter();
            } else {
                validTill = assertion.getSaml1().getConditions().getNotOnOrAfter();
            }

            SecurityToken securityToken = new SecurityToken(assertion.getId(), null, validTill.toDate());
            securityToken.setToken(assertion.getElement());
            securityToken.setPrincipal(principal);
            
            if (tokenRealm != null) {
                Properties props = new Properties();
                props.setProperty(STSConstants.TOKEN_REALM, tokenRealm);
                securityToken.setProperties(props);
            }

            int hash = Arrays.hashCode(signatureValue);
            securityToken.setTokenHash(hash);
            String identifier = Integer.toString(hash);
            tokenStore.add(identifier, securityToken);
        }
    }

    
    private DateTime getExpiryDate(AssertionWrapper assertion) {
        if (assertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
            return assertion.getSaml2().getConditions().getNotOnOrAfter();
        } else {
            return assertion.getSaml1().getConditions().getNotOnOrAfter();
        }
    }

    private static class ProofOfPossessionValidator extends AbstractSamlPolicyValidator {
        
        public boolean checkProofOfPossession(
            TokenRenewerParameters tokenParameters,
            SAMLKeyInfo subjectKeyInfo
        ) {
            MessageContext messageContext = tokenParameters.getWebServiceContext().getMessageContext();
            final List<WSHandlerResult> handlerResults = 
                CastUtils.cast((List<?>) messageContext.get(WSHandlerConstants.RECV_RESULTS));

            List<WSSecurityEngineResult> signedResults = new ArrayList<WSSecurityEngineResult>();
            if (handlerResults != null && handlerResults.size() > 0) {
                WSHandlerResult handlerResult = handlerResults.get(0);
                List<WSSecurityEngineResult> results = handlerResult.getResults();
                
                WSSecurityUtil.fetchAllActionResults(results, WSConstants.SIGN, signedResults);
                WSSecurityUtil.fetchAllActionResults(results, WSConstants.UT_SIGN, signedResults);
            }
            
            TLSSessionInfo tlsInfo = (TLSSessionInfo)messageContext.get(TLSSessionInfo.class.getName());
            Certificate[] tlsCerts = null;
            if (tlsInfo != null) {
                tlsCerts = tlsInfo.getPeerCertificates();
            }
            
            return compareCredentials(subjectKeyInfo, signedResults, tlsCerts);
        }
    }
}
