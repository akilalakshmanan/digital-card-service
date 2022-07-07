package io.mosip.digitalcard.service.impl;

import io.mosip.digitalcard.constant.DigitalCardServiceErrorCodes;
import io.mosip.digitalcard.controller.DigitalCardController;
import io.mosip.digitalcard.dto.*;
import io.mosip.digitalcard.entity.DigitalCardTransactionEntity;
import io.mosip.digitalcard.exception.ApiNotAccessibleException;
import io.mosip.digitalcard.exception.DataShareException;
import io.mosip.digitalcard.exception.DigitalCardServiceException;
import io.mosip.digitalcard.repositories.DigitalCardTransactionRepository;
import io.mosip.digitalcard.service.DigitalCardService;
import io.mosip.digitalcard.service.CardGeneratorService;
import io.mosip.digitalcard.util.*;
import io.mosip.digitalcard.websub.CredentialStatusEvent;
import io.mosip.digitalcard.websub.StatusEvent;
import io.mosip.digitalcard.websub.WebSubSubscriptionHelper;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.websub.model.EventModel;
import io.mosip.vercred.CredentialsVerifier;
import org.json.JSONObject;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The DigitalCardServiceImpl.
 *
 * @author Dhanendra
 */
@Service
public class DigitalCardServiceImpl implements DigitalCardService {

    @Autowired
    private CardGeneratorService pdfCardServiceImpl;

    @Autowired
    private CredentialUtil credentialUtil;

    @Autowired
    Utility utility;

    @Autowired
    RestClient restClient;

    @Autowired
    private EncryptionUtil encryptionUtil;

    @Autowired
    private CredentialsVerifier credentialsVerifier;

    @Autowired
    private DataShareUtil dataShareUtil;

    @Autowired
    private WebSubSubscriptionHelper webSubSubscriptionHelper;

    @Autowired
    DigitalCardTransactionRepository digitalCardTransactionRepository;

    /** The Constant VALUE. */
    private static final String VALUE = "value";

    @Value("${mosip.digitalcard.datashare.partner.id}")
    private String dataSharePartnerId;

    @Value("${mosip.digitalcard.datashare.policy.id}")
    private String dataSharePolicyId;

    @Value("${mosip.digitalcard.verify.credentials.flag:true}")
    private boolean verifyCredentialsFlag;

    @Value("${mosip.digitalcard.credentials.request.initiate.flag:true}")
    private boolean isInitiateFlag;

    @Value("${mosip.digitalcard.pdf.password.enable.flag:true}")
    private boolean isPasswordProtected;

    @Value("${mosip.digitalcard.credential.request.partner.id}")
    private String partnerId;

    @Value("${mosip.digitalcard.credential.type}")
    private String credentialType;

    @Value("${mosip.digitalcard.websub.publish.topic:CREDENTIAL_STATUS_UPDATE}")
    private String topic;

    @Value("${mosip.digitalcard.uincard.password}")
    private String digitalCardPassword;

    @Value("${mosip.template-language}")
    private String templateLang;


    Logger logger = DigitalCardRepoLogger.getLogger(DigitalCardController.class);

    public boolean generateDigitalCard(String credential, String credentialType,String dataShareUrl,String eventId,String transactionId) {
        boolean isGenerated = false;
        String decryptedCredential=null;
        String password=null;
        try {
            if (dataShareUrl != null) {
                credential = restClient.getForObject(dataShareUrl, String.class);
            }
            decryptedCredential = encryptionUtil.decryptData(credential);
            JSONObject jsonObject = new org.json.JSONObject(decryptedCredential);
            JSONObject decryptedCredentialJson = jsonObject.getJSONObject("credentialSubject");
            if (verifyCredentialsFlag){
                logger.info("Configured received credentials to be verified. Flag {}", verifyCredentialsFlag);
                boolean verified =credentialsVerifier.verifyCredentials(decryptedCredential);
                if (!verified) {
                    logger.error("Received Credentials failed in verifiable credential verify method. So, digital card is not getting generated." +
                            " Id: {}, Transaction Id: {}",eventId, transactionId);
                    return false;
                }
            }
            if (isPasswordProtected) {
                password = getPassword(decryptedCredentialJson);
            }
            byte[] pdfBytes=pdfCardServiceImpl.generateCard(decryptedCredentialJson, credentialType,password);
            digitalCardStatusUpdate(transactionId,pdfBytes,credentialType,getRid(decryptedCredentialJson.get("id")));
        }catch (Exception e){
            logger.error(DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_GENERATED.getErrorMessage() , e);
            isGenerated = false;
        }
        return isGenerated;
    }

    @Override
    public DigitalCardStatusResponseDto getDigitalCard(String rid) {
        String pdfByteString=null;
        try {
            DigitalCardTransactionEntity digitalCardTransactionEntity=digitalCardTransactionRepository.findByRID(rid);
            if(digitalCardTransactionEntity!=null && digitalCardTransactionEntity.getDataShareUrl()!=null){
                DigitalCardStatusResponseDto digitalCardStatusResponseDto=new DigitalCardStatusResponseDto();
                digitalCardStatusResponseDto.setId(digitalCardTransactionEntity.getrid());
                digitalCardStatusResponseDto.setStatusCode(digitalCardTransactionEntity.getStatusCode());
                digitalCardStatusResponseDto.setUrl(digitalCardTransactionEntity.getDataShareUrl());
                return digitalCardStatusResponseDto;
            }
            if(isInitiateFlag && digitalCardTransactionEntity==null) {
                CredentialRequestDto credentialRequestDto=new CredentialRequestDto();
                credentialRequestDto.setCredentialType(credentialType);
                credentialRequestDto.setIssuer(partnerId);
                credentialRequestDto.setId(rid);
                CredentialResponse credentialResponse = credentialUtil.reqCredential(credentialRequestDto);
                saveTransactionDetails(credentialResponse, null);
            }
            throw new DigitalCardServiceException(DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_CREATED.getErrorCode(),DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_CREATED.getErrorMessage());
        } catch (Exception e) {
            throw new DigitalCardServiceException(DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_GENERATED.getErrorCode(),DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_GENERATED.getErrorMessage());
        }
    }

    @Override
    public boolean initiateCredentialRequest(EventModel eventModel) {
        boolean isCreated=false;
        String pdfByteString = null;
        CredentialRequestDto credentialRequestDto = new CredentialRequestDto();
        credentialRequestDto.setCredentialType(credentialType);
        credentialRequestDto.setIssuer(partnerId);
        credentialRequestDto.setId(eventModel.getEvent().getData().get("registration_id").toString());
        try {
            CredentialResponse credentialResponse = credentialUtil.reqCredential(credentialRequestDto);
            saveTransactionDetails(credentialResponse, eventModel.getEvent().getData().get("id_hash").toString());
            isCreated=true;
        } catch (DigitalCardServiceException e) {
            isCreated=false;
            logger.error(DigitalCardServiceErrorCodes.DIGITAL_CARD_NOT_GENERATED.getErrorMessage(),e);
        }
        return isCreated;
    }


    private void saveTransactionDetails(CredentialResponse credentialResponse, String idHash){
        DigitalCardTransactionEntity digitalCardEntity=new DigitalCardTransactionEntity();
        digitalCardEntity.setrid(credentialResponse.getId());
        digitalCardEntity.setUinSaltedHash(idHash);
        digitalCardEntity.setCredentialId(credentialResponse.getRequestId());
        digitalCardEntity.setCreateDateTime(LocalDateTime.now());
        digitalCardEntity.setCreatedBy(Utility.getUser());
        digitalCardEntity.setStatusCode("NEW");
        digitalCardTransactionRepository.save(digitalCardEntity);

    }
    private void digitalCardStatusUpdate(String requestId, byte[] data, String credentialType, String rid)
            throws DataShareException, ApiNotAccessibleException, IOException, Exception {
        DataShareDto dataShareDto = null;
        dataShareDto = dataShareUtil.getDataShare(data, dataSharePolicyId, dataSharePartnerId);
        CredentialStatusEvent creEvent = new CredentialStatusEvent();
        LocalDateTime currentDtime = DateUtils.getUTCCurrentDateTime();
        digitalCardTransactionRepository.updateTransactionDetails(rid,"AVAILABLE", dataShareDto.getUrl(),LocalDateTime.now(),Utility.getUser());
        StatusEvent sEvent = new StatusEvent();
        sEvent.setId(UUID.randomUUID().toString());
        sEvent.setRequestId(requestId);
        sEvent.setStatus("STORED");
        sEvent.setUrl(dataShareDto.getUrl());
        sEvent.setTimestamp(Timestamp.valueOf(currentDtime).toString());
        creEvent.setPublishedOn(LocalDateTime.now().toString());
        creEvent.setPublisher("DIGITAL_CARD_SERVICE");
        creEvent.setTopic(topic);
        creEvent.setEvent(sEvent);
        webSubSubscriptionHelper.digitalCardStatusUpdateEvent(topic, creEvent);
        logger.info("publish event for topic : {} and rid : {}",topic,rid);
    }
    private String getRid(Object id) {
        String rid= id.toString().split("/credentials/")[1];
        return rid;
    }
    /**
     * Gets the password.
     *
     * @param jsonObject
     * @return
     * @throws Exception
     */
    private String getPassword(JSONObject jsonObject) throws Exception {
        String[] attributes = digitalCardPassword.split("\\|");
        List<String> list = new ArrayList<>(Arrays.asList(attributes));

        Iterator<String> it = list.iterator();
        String uinCardPd = "";
        Object obj=null;
        while (it.hasNext()) {
            String key = it.next().trim();

            Object object = jsonObject.get(key);
            if (object != null) {
                try {
                    obj = new JSONParser().parse(object.toString());
                } catch (Exception e) {
                    obj = object;
                }
            }
            if (obj instanceof JSONArray) {
                // JSONArray node = JsonUtil.getJSONArray(demographicIdentity, value);
                SimpleType[] jsonValues = Utility.mapJsonNodeToJavaObject(SimpleType.class, (JSONArray) obj);
                uinCardPd = uinCardPd.concat(getParameter(jsonValues, templateLang).substring(0,4));
            } else if (object instanceof org.json.simple.JSONObject) {
                org.json.simple.JSONObject json = (org.json.simple.JSONObject) object;
                uinCardPd = uinCardPd.concat((String) json.get(VALUE));
            } else {
                uinCardPd = uinCardPd.concat((String) object.toString().substring(0,4));
            }
        }
        return uinCardPd;
    }


    /**
     * Gets the parameter.
     *
     * @param jsonValues
     *            the json values
     * @param langCode
     *            the lang code
     * @return the parameter
     */
    private String getParameter(SimpleType[] jsonValues, String langCode) {

        String parameter = null;
        if (jsonValues != null) {
            for (int count = 0; count < jsonValues.length; count++) {
                String lang = jsonValues[count].getLanguage();
                if (langCode.contains(lang)) {
                    parameter = jsonValues[count].getValue();
                    break;
                }
            }
        }
        return parameter;
    }

}
