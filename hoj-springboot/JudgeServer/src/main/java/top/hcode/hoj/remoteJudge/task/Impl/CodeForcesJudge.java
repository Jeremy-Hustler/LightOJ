package top.hcode.hoj.remoteJudge.task.Impl;

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.NicelyResynchronizingAjaxController;
import com.gargoylesoftware.htmlunit.SilentCssErrorHandler;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.*;
import lombok.extern.slf4j.Slf4j;
import top.hcode.hoj.remoteJudge.task.RemoteJudgeStrategy;
import top.hcode.hoj.util.Constants;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Slf4j(topic = "hoj")
public class CodeForcesJudge implements RemoteJudgeStrategy {
    public static final String HOST = "https://codeforces.com/";
    public static final String LOGIN_URL = "enter";
    public static final String SUBMIT_URL = "problemset/submit";
    public static final String SUBMISSION_RESULT_URL = "api/user.status?handle=%s&from=1&count=30";
    public static final String CE_INFO_URL = "data/submitSource";

    private static final Map<String, Constants.Judge> statusMap = new HashMap<String, Constants.Judge>() {{
        put("FAILED", Constants.Judge.STATUS_SUBMITTED_FAILED);
        put("OK", Constants.Judge.STATUS_ACCEPTED);
        put("PARTIAL", Constants.Judge.STATUS_PARTIAL_ACCEPTED);
        put("COMPILATION_ERROR", Constants.Judge.STATUS_COMPILE_ERROR);
        put("RUNTIME_ERROR", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("WRONG_ANSWER", Constants.Judge.STATUS_WRONG_ANSWER);
        put("PRESENTATION_ERROR", Constants.Judge.STATUS_PRESENTATION_ERROR);
        put("TIME_LIMIT_EXCEEDED", Constants.Judge.STATUS_TIME_LIMIT_EXCEEDED);
        put("MEMORY_LIMIT_EXCEEDED", Constants.Judge.STATUS_MEMORY_LIMIT_EXCEEDED);
        put("IDLENESS_LIMIT_EXCEEDED", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("SECURITY_VIOLATED", Constants.Judge.STATUS_RUNTIME_ERROR);
        put("CRASHED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("INPUT_PREPARATION_CRASHED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("CHALLENGED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("SKIPPED", Constants.Judge.STATUS_SYSTEM_ERROR);
        put("TESTING", Constants.Judge.STATUS_JUDGING);
        put("REJECTED", Constants.Judge.STATUS_SYSTEM_ERROR);
    }};


    @Override
    public Map<String, Object> submit(String username, String password, String problemId, String language, String userCode) throws Exception {
        if (problemId == null || userCode == null) {
            return null;
        }

        Map<String, Object> loginUtils = getLoginUtils(username, password);

        if (loginUtils == null) {
            log.error("进行题目提交时发生错误：登录失败，可能原因账号或密码错误，登录失败！" + CodeForcesJudge.class.getName() + "，题号:" + problemId);
            return null;
        }

        try (WebClient webClient = (WebClient) loginUtils.getOrDefault("webClient", null)) {
            submitCode(webClient, problemId, getLanguage(language), userCode);
        }

        // 获取提交的题目id

        Long maxRunId = getMaxRunId(username, problemId);

        return MapUtil.builder(new HashMap<String, Object>())
                .put("runId", maxRunId)
                .put("cookies", null)
                .map();
    }

    private Long getMaxRunId(String username, String problemId) throws InterruptedException {
        int retryNum = 0;
        String url = String.format(SUBMISSION_RESULT_URL, username);
        HttpRequest httpRequest = HttpUtil.createGet(HOST + url);
        httpRequest.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.101 Safari/537.36 Edg/91.0.864.48");
        httpRequest.disableCache();
        HttpResponse httpResponse = httpRequest.execute();
        // 防止cf的nginx限制访问频率，重试10次
        while (httpResponse.getStatus() != 200 && retryNum != 10) {
            TimeUnit.SECONDS.sleep(3);
            httpResponse = httpRequest.execute();
            retryNum++;
        }

        String contestNum = problemId.replaceAll("\\D.*", "");
        String problemNum = problemId.replaceAll("^\\d*", "");
        try {
            Map<String, Object> json = JSONUtil.parseObj(httpResponse.body());
            List<Map<String, Object>> results = (List<Map<String, Object>>) json.get("result");
            for (Map<String, Object> result : results) {
                Long runId = Long.valueOf(result.get("id").toString());
                Map<String, Object> problem = (Map<String, Object>) result.get("problem");
                if (contestNum.equals(problem.get("contestId").toString()) &&
                        problemNum.equals(problem.get("index").toString())) {
                    return runId;
                }
            }
        } catch (Exception e) {
            log.error("进行题目获取runID发生错误：获取提交ID失败，" + CodeForcesJudge.class.getName()
                    + "，题号:" + problemId + "，异常描述：" + e.getMessage());
            return -1L;
        }
        return -1L;
    }

    @Override
    public Map<String, Object> result(Long submitId, String username, String cookies) {

        String url = HOST + String.format(SUBMISSION_RESULT_URL, username);

        String resJson = HttpUtil.createGet(url)
                .timeout(30000)
                .execute()
                .body();

        JSONObject jsonObject = JSONUtil.parseObj(resJson);

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("status", Constants.Judge.STATUS_JUDGING.getStatus());

        JSONArray results = (JSONArray) jsonObject.get("result");

        for (Object tmp : results) {
            JSONObject result = (JSONObject) tmp;
            long runId = Long.parseLong(result.get("id").toString());
            if (runId == submitId) {
                String verdict = (String) result.get("verdict");
                Constants.Judge statusType = statusMap.get(verdict);
                if (statusType == Constants.Judge.STATUS_JUDGING) {
                    return MapUtil.builder(new HashMap<String, Object>())
                            .put("status", statusType.getStatus()).build();
                }
                resultMap.put("time", result.get("timeConsumedMillis"));
                resultMap.put("memory", result.get("memoryConsumedBytes"));
                Constants.Judge resultStatus = statusMap.get(verdict);
                if (resultStatus == Constants.Judge.STATUS_COMPILE_ERROR) {

                    String html = HttpUtil.createGet(HOST)
                            .timeout(30000).execute().body();
                    String csrfToken = ReUtil.get("data-csrf='(\\w+)'", html, 1);

                    String ceJson = HttpUtil.createPost(HOST + CE_INFO_URL)
                            .form(MapUtil
                                    .builder(new HashMap<String, Object>())
                                    .put("csrf_token", csrfToken)
                                    .put("submissionId", submitId.toString()).map())
                            .timeout(30000)
                            .execute()
                            .body();
                    JSONObject CEInfoJson = JSONUtil.parseObj(ceJson);
                    String CEInfo = CEInfoJson.getStr("checkerStdoutAndStderr#1");

                    resultMap.put("CEInfo", CEInfo);
                }
                resultMap.put("status", resultStatus.getStatus());
                return resultMap;
            }
        }
        return resultMap;
    }

    @Override
    public Map<String, Object> getLoginUtils(String username, String password) throws Exception {
        // 模拟一个浏览器
        WebClient webClient = new WebClient(BrowserVersion.CHROME);
        // 设置webClient的相关参数
        webClient.setCssErrorHandler(new SilentCssErrorHandler());
        //设置ajax
        webClient.setAjaxController(new NicelyResynchronizingAjaxController());
        //设置禁止js
        webClient.getOptions().setJavaScriptEnabled(false);
        //CSS渲染禁止
        webClient.getOptions().setCssEnabled(false);
        //超时时间
        webClient.getOptions().setTimeout(40000);

        //设置js抛出异常:false
        webClient.getOptions().setThrowExceptionOnScriptError(false);
        //允许重定向
        webClient.getOptions().setRedirectEnabled(true);
        //允许cookie
        webClient.getCookieManager().setCookiesEnabled(true);

        webClient.getOptions().setUseInsecureSSL(true);
        // 模拟浏览器打开一个目标网址
        HtmlPage page = webClient.getPage(HOST + LOGIN_URL);

        HtmlForm form = (HtmlForm) page.getElementById("enterForm");
        HtmlTextInput usernameInput = form.getInputByName("handleOrEmail");
        HtmlPasswordInput passwordInput = form.getInputByName("password");
        usernameInput.setValueAttribute(username);  //用户名
        passwordInput.setValueAttribute(password);  //密码

        HtmlSubmitInput button = (HtmlSubmitInput) page.getByXPath("//input[@class='submit']").get(0);

        HtmlPage retPage = button.click();

        if (retPage.getUrl().toString().equals(HOST)) {
            return MapUtil.builder(new HashMap<String, Object>()).put("webClient", webClient).map();
        } else {
            webClient.close();
            return null;
        }
    }

    public void submitCode(WebClient webClient, String problemID, String languageID, String code) throws IOException {
        // 模拟浏览器打开一个目标网址
        HtmlPage page = webClient.getPage(HOST + SUBMIT_URL);

        HtmlForm form = (HtmlForm) page.getByXPath("//form[@class='submit-form']").get(0);

        // 题号
        HtmlTextInput problemCode = form.getInputByName("submittedProblemCode");
        problemCode.setValueAttribute(problemID);
        // 编程语言
        HtmlSelect programTypeId = form.getSelectByName("programTypeId");
        HtmlOption optionByValue = programTypeId.getOptionByValue(languageID);
        optionByValue.click();
        // 代码
        HtmlTextArea source = form.getTextAreaByName("source");
        source.setText(code + getRandomBlankString());

        HtmlSubmitInput button = (HtmlSubmitInput) page.getByXPath("//input[@class='submit']").get(0);
        button.click();
    }

    @Override
    public String getLanguage(String language) {
        if (language.startsWith("GNU GCC C11")) {
            return "43";
        } else if (language.startsWith("Clang++17 Diagnostics")) {
            return "52";
        } else if (language.startsWith("GNU G++11")) {
            return "42";
        } else if (language.startsWith("GNU G++14")) {
            return "50";
        } else if (language.startsWith("GNU G++17")) {
            return "54";
        } else if (language.startsWith("Microsoft Visual C++ 2010")) {
            return "2";
        } else if (language.startsWith("Microsoft Visual C++ 2017")) {
            return "59";
        } else if (language.startsWith("C# 8, .NET Core")) {
            return "65";
        } else if (language.startsWith("C# Mono")) {
            return "9";
        } else if (language.startsWith("D DMD32")) {
            return "28";
        } else if (language.startsWith("Go")) {
            return "32";
        } else if (language.startsWith("Haskell GHC")) {
            return "12";
        } else if (language.startsWith("Java 11")) {
            return "60";
        } else if (language.startsWith("Java 1.8")) {
            return "36";
        } else if (language.startsWith("Kotlin")) {
            return "48";
        } else if (language.startsWith("OCaml")) {
            return "19";
        } else if (language.startsWith("Delphi")) {
            return "3";
        } else if (language.startsWith("Free Pascal")) {
            return "4";
        } else if (language.startsWith("PascalABC.NET")) {
            return "51";
        } else if (language.startsWith("Perl")) {
            return "13";
        } else if (language.startsWith("PHP")) {
            return "6";
        } else if (language.startsWith("Python 2")) {
            return "7";
        } else if (language.startsWith("Python 3")) {
            return "31";
        } else if (language.startsWith("PyPy 2")) {
            return "40";
        } else if (language.startsWith("PyPy 3")) {
            return "41";
        } else if (language.startsWith("Ruby")) {
            return "67";
        } else if (language.startsWith("Rust")) {
            return "49";
        } else if (language.startsWith("Scala")) {
            return "20";
        } else if (language.startsWith("JavaScript")) {
            return "34";
        } else if (language.startsWith("Node.js")) {
            return "55";
        } else {
            return null;
        }
    }

    protected String getRandomBlankString() {
        StringBuilder string = new StringBuilder("\n");
        int random = new Random().nextInt(Integer.MAX_VALUE);
        while (random > 0) {
            string.append(random % 2 == 0 ? ' ' : '\t');
            random /= 2;
        }
        return string.toString();
    }

}
