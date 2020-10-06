/*
 * Created by David Luedtke (MrKinau)
 * 2019/5/3
 */

package systems.kinau.fishingbot.auth;

import org.apache.commons.codec.Charsets;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import systems.kinau.fishingbot.FishingBot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class Authenticator {

    private final String AUTH_ENDPOINT = "https://authserver.mojang.com";

    private final HttpClient client;
    private final File accountFile;

    public Authenticator(File accountFile) {
        this.client = HttpClientBuilder.create().build();
        this.accountFile = accountFile;
    }

    public AuthData authenticate() {
        String userName = FishingBot.getInstance().getConfig().getUserName();
        String password = FishingBot.getInstance().getConfig().getPassword();
        if (accountFile.exists()) {
            try {
                List<String> content = Files.readAllLines(Paths.get(accountFile.toURI()));
                String json = String.join("", content);
                JSONObject rootObj = (JSONObject) new JSONParser().parse(json);
                String accessToken = (String) rootObj.get("accessToken");
                String clientToken = (String) rootObj.get("clientToken");
                String loginName = (String) rootObj.get("loginName");
                String accountName = (String) rootObj.get("accountName");
                String profileId = (String) rootObj.get("profileId");
                if (loginName.equals(FishingBot.getInstance().getConfig().getUserName()))
                    return authenticateWithTokens(accessToken, clientToken, loginName, password, accountName, profileId);
                else
                    return authenticateWithUsernamePassword(userName, password);
            } catch (Exception e) {
                FishingBot.getLog().warning("Could not read " + accountFile.getName() + ": " + e.getMessage());
                return authenticateWithUsernamePassword(userName, password);
            }
        } else
            return authenticateWithUsernamePassword(userName, password);
    }

    public AuthData authenticateWithTokens(String accessToken, String clientToken, String loginName, String password, String accountName, String profileId) {
        JSONObject validateRequest = new JSONObject();
        validateRequest.put("accessToken", accessToken);
        validateRequest.put("clientToken", clientToken);

        try {
            HttpUriRequest request = RequestBuilder.post()
                    .setUri(AUTH_ENDPOINT + "/validate")
                    .setHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .setEntity(new StringEntity(validateRequest.toString()))
                    .build();
            FishingBot.getLog().info("Try to authenticate with accessToken");
            HttpResponse answer = client.execute(request);
            if (answer.getStatusLine().getStatusCode() == 403) {
                JSONObject responseJson = (JSONObject) new JSONParser().parse(EntityUtils.toString(answer.getEntity(), Charsets.UTF_8));
                FishingBot.getLog().warning("AccessToken is invalid: " + responseJson.get("errorMessage") + ", trying to refresh it");
                return refresh(accessToken, clientToken, loginName, password);
            } else if (answer.getStatusLine().getStatusCode() == 204) {
                FishingBot.getLog().info("Authentication successful!");
                return new AuthData(accessToken, clientToken, profileId, accountName);
            } else {
                FishingBot.getLog().warning("AccessToken is irrecoverably. Using password authentication");
                return authenticateWithUsernamePassword(loginName, password);
            }
        } catch (IOException | ParseException e) {
            FishingBot.getLog().severe("Error while connecting to: " + AUTH_ENDPOINT + "/validate");
        }
        return null;
    }

    private AuthData refresh(String accessToken, String clientToken, String loginName, String password) {
        JSONObject validateRequest = new JSONObject();
        validateRequest.put("accessToken", accessToken);
        validateRequest.put("clientToken", clientToken);

        try {
            HttpUriRequest request = RequestBuilder.post()
                    .setUri(AUTH_ENDPOINT + "/refresh")
                    .setHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .setEntity(new StringEntity(validateRequest.toString()))
                    .build();
            FishingBot.getLog().info("Try to refresh accessToken");
            HttpResponse answer = client.execute(request);

            if (answer.getStatusLine().getStatusCode() == 200) {
                JSONObject responseJson = (JSONObject) new JSONParser().parse(EntityUtils.toString(answer.getEntity(), Charsets.UTF_8));
                String newAccessToken = (String) responseJson.get("accessToken");
                String newUserName = (String) ((JSONObject)responseJson.get("selectedProfile")).get("name");
                String newUUID = (String) ((JSONObject)responseJson.get("selectedProfile")).get("id");
                FishingBot.getLog().info("Authentication successful!");
                writeAccountFile(newAccessToken, clientToken, loginName, newUserName, newUUID);
                return new AuthData(newAccessToken, clientToken, newUUID, newUserName);
            } else {
                FishingBot.getLog().warning("AccessToken is irrecoverably. Using password authentication");
                return authenticateWithUsernamePassword(loginName, password);
            }
        } catch (IOException | ParseException e) {
            FishingBot.getLog().severe("Error while connecting to: " + AUTH_ENDPOINT + "/validate");
        }
        return null;
    }

    public AuthData authenticateWithUsernamePassword(String loginName, String password) {
        JSONObject data = new JSONObject();
        JSONObject agent = new JSONObject();
        agent.put("name", "minecraft");
        agent.put("version", "1");
        data.put("agent", agent);
        data.put("username", loginName);
        data.put("password", password);

        try {
            HttpUriRequest request = RequestBuilder.post()
                    .setUri(AUTH_ENDPOINT + "/authenticate")
                    .setHeader(HttpHeaders.CONTENT_TYPE, "application/json; charset=utf-8")
                    .setEntity(new StringEntity(data.toString()))
                    .build();

            HttpResponse answer = client.execute(request);
            FishingBot.getLog().info("Try to authenticate " + loginName + ":********");

            if (answer.getStatusLine().getStatusCode() != 200) {
                FishingBot.getLog().severe("Authentication failed with code " + answer.getStatusLine().getStatusCode() + " from " + AUTH_ENDPOINT + ": " + answer.getStatusLine());
                FishingBot.getLog().severe(EntityUtils.toString(answer.getEntity(), Charsets.UTF_8));
                return null;
            }

            JSONObject responseJson = (JSONObject) new JSONParser().parse(EntityUtils.toString(answer.getEntity(), Charsets.UTF_8));
            String accessToken = (String) responseJson.get("accessToken");
            String clientToken = (String) responseJson.get("clientToken");
            String profile = (String) ((JSONObject)responseJson.get("selectedProfile")).get("id");
            String username = (String) ((JSONObject)responseJson.get("selectedProfile")).get("name");
            FishingBot.getLog().info("Authentication successful!");
            writeAccountFile(accessToken, clientToken, loginName, username, profile);
            return new AuthData(accessToken, clientToken, profile, username);
        } catch (IOException | ParseException e) {
            FishingBot.getLog().severe("Error while connecting to: " + AUTH_ENDPOINT + "/authenticate");
        }
        return null;
    }

    private void writeAccountFile(String accessToken, String clientToken, String loginName, String accountName, String profileId) {
        org.json.JSONObject rootObj = new org.json.JSONObject();
        rootObj.put("accessToken", accessToken);
        rootObj.put("clientToken", clientToken);
        rootObj.put("loginName", loginName);
        rootObj.put("accountName", accountName);
        rootObj.put("profileId", profileId);
        String output = rootObj.toString(4);
        try {
            Files.write(Paths.get(accountFile.toURI()), Arrays.asList(output.split("\n")));
        } catch (IOException e) {
            FishingBot.getLog().warning("Could not save " + accountFile.getName() + ": " + e.getMessage());
        }
    }

}
