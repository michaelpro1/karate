/*
 * The MIT License
 *
 * Copyright 2018 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.intuit.karate.FileUtils;
import com.intuit.karate.JsonUtils;
import com.intuit.karate.Match;
import com.intuit.karate.Script;
import com.intuit.karate.ScriptValueMap;
import com.intuit.karate.XmlUtils;
import com.intuit.karate.http.HttpRequest;
import com.intuit.karate.http.HttpResponse;
import com.intuit.karate.http.HttpUtils;

/**
 * A wrapper class to run multiple mock files, picking first match based on path, method, header.
 *
 */
public class FeaturesBackend {

    private final List<FeatureBackend> featureBackends;
    private static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, DELETE, PATCH";
    private static final String DUMMY_FEATURE =
            "@ignore\nFeature:\n\nBackground:\n\nScenario:\n\n";

//    private final Feature serverFeature;
//    private final StepActions actions;
//    private final ScenarioContext context;

    public FeaturesBackend(Feature feature) {
        this(new Feature[]{feature});
    }

    public FeaturesBackend(Feature[] features) {
        this(features, null);
    }

    public FeaturesBackend(Feature[] features, Map<String, Object> arg) {
        this.featureBackends = Arrays.stream(features).map((feature) -> new FeatureBackend(feature, arg)).collect(
                Collectors.toList());
//        serverFeature = FeatureParser.parseText(null, DUMMY_FEATURE);
//        serverFeature.setName(this.getClass().getName());
//        serverFeature.setCallName(this.getClass().getName());
//        CallContext callContext = new CallContext(null, false);
//        FeatureContext featureContext = new FeatureContext(null, serverFeature, null);
//        actions = new StepActions(featureContext, callContext, null, null);
//        context = actions.context;
        //TODO would be good to pass the root context to backends

        getContext().logger.info("all backends initialized");
    }

    public boolean isCorsEnabled() {
        return featureBackends.stream().anyMatch((fb) -> fb.getContext().getConfig().isCorsEnabled());
    }

    public ScenarioContext getContext() {
        return featureBackends.get(0).getContext();
    }

    public HttpResponse buildResponse(HttpRequest request, long startTime) {
        if("OPTIONS".equals(request.getMethod()) && isCorsEnabled()) {
            return corsCheck(request, startTime);
        }
        //This is not expected to be an actual scenario
        Match match = new Match()
                .text(ScriptValueMap.VAR_REQUEST_URL_BASE, request.getUrlBase())
                .text(ScriptValueMap.VAR_REQUEST_URI, request.getUri())
                .text(ScriptValueMap.VAR_REQUEST_METHOD, request.getMethod())
                .def(ScriptValueMap.VAR_REQUEST_HEADERS, request.getHeaders())
                .def(ScriptValueMap.VAR_RESPONSE_STATUS, 200)
                .def(ScriptValueMap.VAR_REQUEST_PARAMS, request.getParams());
        byte[] requestBytes = request.getBody();
        if (requestBytes != null) {
            match.def(ScriptValueMap.VAR_REQUEST_BYTES, requestBytes);
            String requestString = FileUtils.toString(requestBytes);
            Object requestBody = requestString;
            if (Script.isJson(requestString)) {
                try {
                    requestBody = JsonUtils.toJsonDoc(requestString);
                } catch (Exception e) {
                    getContext().logger.warn("json parsing failed, request data type set to string: {}", e.getMessage());
                }
            } else if (Script.isXml(requestString)) {
                try {
                    requestBody = XmlUtils.toXmlDoc(requestString);
                } catch (Exception e) {
                    getContext().logger.warn("xml parsing failed, request data type set to string: {}", e.getMessage());
                }
            }
            match.def(ScriptValueMap.VAR_REQUEST, requestBody);
        }


        FeatureBackend.FeatureScenarioMatch matchingInfo = getMatchingScenario(match.vars());
        FeatureBackend matchingFeature = matchingInfo.getFeatureBackend();
        Scenario matchingScenario = matchingInfo.getScenario();

        return matchingFeature.buildResponse(request, startTime, matchingScenario, match.vars());
    }

    public HttpResponse corsCheck(HttpRequest request, long startTime) {
        HttpResponse response = new HttpResponse(startTime, System.currentTimeMillis());
        response.setStatus(200);
        response.addHeader(HttpUtils.HEADER_ALLOW, ALLOWED_METHODS);
        response.addHeader(HttpUtils.HEADER_AC_ALLOW_ORIGIN, "*");
        response.addHeader(HttpUtils.HEADER_AC_ALLOW_METHODS, ALLOWED_METHODS);
        List requestHeaders = request.getHeaders().get(HttpUtils.HEADER_AC_REQUEST_HEADERS);
        if (requestHeaders != null) {
            response.putHeader(HttpUtils.HEADER_AC_ALLOW_HEADERS, requestHeaders);
        }
        return response;
    }

    public ScriptValueMap handle(ScriptValueMap args) {
        FeatureBackend.FeatureScenarioMatch matchingInfo = getMatchingScenario(args);
        //TODO what happens when no matching scenario exists?
        return matchingInfo.getFeatureBackend().handle(args, matchingInfo.getScenario());
    }

    /**
     * @param args
     * @return
     */
    public FeatureBackend.FeatureScenarioMatch getMatchingScenario(ScriptValueMap args) {
        FeatureBackend.FeatureScenarioMatch matching = null;
        List<FeatureBackend.FeatureScenarioMatch> matches = new ArrayList<>();
        List<FeatureBackend.FeatureScenarioMatch> defaults = new ArrayList<>();
        for(FeatureBackend featureBackend: featureBackends) {
            //This can be optimised by saying give me the first one
            List<FeatureBackend.FeatureScenarioMatch> featureMatches = featureBackend.getMatchingScenarios(args);
            Scenario defaultMatch = featureBackend.getDefaultScenario(args);

            matches.addAll(featureMatches);
            if(defaultMatch != null)
                defaults.add(new FeatureBackend.FeatureScenarioMatch(featureBackend, defaultMatch, Arrays.asList(0, 0, 0, 0, 0)));

        }
        if(matches.isEmpty() && defaults.isEmpty()) {
            getContext().logger.error("no scenarios matched request");
            return null;
        }
        else {
            matching = matches.stream().max((left, right) -> left.compareScores(right)).orElse(null);
            if(matching == null) {
                matching = defaults.stream().findFirst().get();
                getContext().logger.debug("scenario defaulted: {}#{}", matching.getFeatureBackend().getFeatureName(), matching.getScenario().getName());
            }
            else {
                getContext().logger.debug("scenario picked: {}#{}", matching.getFeatureBackend().getFeatureName(), matching.getScenario().getName());
            }

        }
        return matching;
    }
}
