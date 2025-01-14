package io.metersphere.service;

import io.metersphere.api.dto.BodyFileRequest;
import io.metersphere.api.dto.EnvironmentType;
import io.metersphere.api.dto.definition.request.ElementUtil;
import io.metersphere.api.dto.definition.request.MsTestPlan;
import io.metersphere.api.exec.api.ApiCaseSerialService;
import io.metersphere.api.jmeter.utils.JmxFileUtil;
import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.ApiExecutionQueueDetailMapper;
import io.metersphere.base.mapper.ApiScenarioMapper;
import io.metersphere.base.mapper.PluginMapper;
import io.metersphere.base.mapper.plan.TestPlanApiScenarioMapper;
import io.metersphere.commons.constants.ApiRunMode;
import io.metersphere.commons.constants.PluginScenario;
import io.metersphere.commons.utils.*;
import io.metersphere.dto.AttachmentBodyFile;
import io.metersphere.dto.JmeterRunRequestDTO;
import io.metersphere.dto.ProjectJarConfig;
import io.metersphere.environment.service.BaseEnvGroupProjectService;
import io.metersphere.metadata.service.FileMetadataService;
import io.metersphere.request.BodyFile;
import io.metersphere.utils.LoggerUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jorphan.collections.HashTree;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ApiJMeterFileService {
    @Resource
    private ApiCaseSerialService apiCaseSerialService;
    @Resource
    private ApiScenarioMapper apiScenarioMapper;
    @Resource
    private ApiExecutionQueueDetailMapper executionQueueDetailMapper;
    @Resource
    private BaseEnvGroupProjectService environmentGroupProjectService;
    @Resource
    private TestPlanApiScenarioMapper testPlanApiScenarioMapper;
    @Resource
    private FileMetadataService fileMetadataService;
    @Resource
    private PluginMapper pluginMapper;
    @Resource
    private RedisTemplateService redisTemplateService;

    // 接口测试 用例/接口
    private static final List<String> CASE_MODES = new ArrayList<>() {{
        this.add(ApiRunMode.DEFINITION.name());
        this.add(ApiRunMode.JENKINS_API_PLAN.name());
        this.add(ApiRunMode.API_PLAN.name());
        this.add(ApiRunMode.SCHEDULE_API_PLAN.name());
        this.add(ApiRunMode.MANUAL_PLAN.name());

    }};

    public byte[] downloadJmeterFiles(String runMode, String remoteTestId, String reportId, String reportType, String queueId) {
        Map<String, String> planEnvMap = new HashMap<>();
        JmeterRunRequestDTO runRequest = new JmeterRunRequestDTO(remoteTestId, reportId, runMode);
        runRequest.setReportType(reportType);
        runRequest.setQueueId(queueId);

        ApiScenarioWithBLOBs scenario = null;
        if (StringUtils.equalsAny(runMode, ApiRunMode.SCENARIO_PLAN.name(), ApiRunMode.JENKINS_SCENARIO_PLAN.name(), ApiRunMode.SCHEDULE_SCENARIO_PLAN.name())) {
            // 获取场景用例单独的执行环境
            TestPlanApiScenario planApiScenario = testPlanApiScenarioMapper.selectByPrimaryKey(remoteTestId);
            if (planApiScenario != null) {
                String envType = planApiScenario.getEnvironmentType();
                String environmentGroupId = planApiScenario.getEnvironmentGroupId();
                String environment = planApiScenario.getEnvironment();
                if (StringUtils.equals(envType, EnvironmentType.JSON.name()) && StringUtils.isNotBlank(environment)) {
                    planEnvMap = JSON.parseObject(environment, Map.class);
                } else if (StringUtils.equals(envType, EnvironmentType.GROUP.name()) && StringUtils.isNotBlank(environmentGroupId)) {
                    planEnvMap = environmentGroupProjectService.getEnvMap(environmentGroupId);
                }
                scenario = apiScenarioMapper.selectByPrimaryKey(planApiScenario.getApiScenarioId());
            }
        }

        ApiExecutionQueueDetail detail = executionQueueDetailMapper.selectByPrimaryKey(queueId);
        if (detail == null) {
            ApiExecutionQueueDetailExample example = new ApiExecutionQueueDetailExample();
            example.createCriteria().andReportIdEqualTo(reportId);
            List<ApiExecutionQueueDetail> list = executionQueueDetailMapper.selectByExampleWithBLOBs(example);
            if (CollectionUtils.isNotEmpty(list)) {
                detail = list.get(0);
            }
        }
        if (detail != null) {
            runRequest.setRetryEnable(detail.getRetryEnable());
            runRequest.setRetryNum(detail.getRetryNumber());
        }
        Map<String, String> envMap = new LinkedHashMap<>();
        if (detail != null && StringUtils.isNotEmpty(detail.getEvnMap())) {
            envMap = JSON.parseObject(detail.getEvnMap(), Map.class);
        }
        if (MapUtils.isEmpty(envMap)) {
            LoggerUtil.info("测试资源：【" + remoteTestId + "】, 报告【" + reportId + "】未重新选择环境");
        }
        HashTree hashTree = null;
        if (CASE_MODES.contains(runMode)) {
            hashTree = apiCaseSerialService.generateHashTree(remoteTestId, envMap, runRequest);
        } else {
            if (scenario == null) {
                scenario = apiScenarioMapper.selectByPrimaryKey(remoteTestId);
            }
            if (scenario == null) {
                // 清除队列
                executionQueueDetailMapper.deleteByPrimaryKey(queueId);
            }
            if (MapUtils.isNotEmpty(envMap)) {
                planEnvMap = envMap;
            } else if (!StringUtils.equalsAny(runMode, ApiRunMode.SCENARIO_PLAN.name(), ApiRunMode.SCHEDULE_SCENARIO_PLAN.name())) {
                String envType = scenario.getEnvironmentType();
                String envJson = scenario.getEnvironmentJson();
                String envGroupId = scenario.getEnvironmentGroupId();
                if (StringUtils.equals(envType, EnvironmentType.JSON.name()) && StringUtils.isNotBlank(envJson)) {
                    planEnvMap = JSON.parseObject(envJson, Map.class);
                } else if (StringUtils.equals(envType, EnvironmentType.GROUP.name()) && StringUtils.isNotBlank(envGroupId)) {
                    planEnvMap = environmentGroupProjectService.getEnvMap(envGroupId);
                }
            }
            hashTree = GenerateHashTreeUtil.generateHashTree(scenario, planEnvMap, runRequest);
        }
        if (hashTree != null) {
            ElementUtil.coverArguments(hashTree);
        }
        return zipFilesToByteArray((reportId + "_" + remoteTestId), reportId, hashTree);
    }

    public byte[] downloadJmeterJar() {
        Map<String, byte[]> files = new HashMap<>();
        // 获取JAR
        Map<String, byte[]> jarFiles = this.getJar(null);
        if (!MapUtils.isEmpty(jarFiles)) {
            for (String k : jarFiles.keySet()) {
                byte[] v = jarFiles.get(k);
                files.put(k, v);
            }
        }
        return listBytesToZip(files);
    }

    public byte[] downloadJmeterJar(Map<String, List<ProjectJarConfig>> map) {
        Map<String, byte[]> files = new HashMap<>();
        if (MapUtils.isNotEmpty(map)) {
            //获取文件内容
            FileMetadataService fileMetadataService = CommonBeanFactory.getBean(FileMetadataService.class);
            map.forEach((key, value) -> {
                //历史数据
                value.forEach(s -> {
                    //获取文件内容;
                    // 兼容历史数据
                    byte[] bytes = fileMetadataService.getContent(s.getId());
                    files.put(StringUtils.join(
                            key,
                            File.separator,
                            s.getId(),
                            File.separator,
                            String.valueOf(s.getUpdateTime()), ".jar"), bytes);
                });
            });
        }
        return listBytesToZip(files);
    }

    public byte[] downloadPluginJar(List<String> pluginIds) {
        Map<String, byte[]> files = new HashMap<>();
        if (CollectionUtils.isNotEmpty(pluginIds)) {
            // 获取JAR
            Map<String, byte[]> jarFiles = this.getPlugJar(pluginIds);
            if (MapUtils.isNotEmpty(jarFiles)) {
                for (String k : jarFiles.keySet()) {
                    byte[] v = jarFiles.get(k);
                    files.put(k, v);
                }
            }
        }
        return listBytesToZip(files);
    }

    private Map<String, byte[]> getJar(String projectId) {
        Map<String, byte[]> jarFiles = new LinkedHashMap<>();
        FileMetadataService jarConfigService = CommonBeanFactory.getBean(FileMetadataService.class);
        if (jarConfigService != null) {
            List<String> files = jarConfigService.getJar(new ArrayList<>() {{
                this.add(projectId);
            }});
            files.forEach(path -> {
                File file = new File(path);
                if (file.isDirectory() && !path.endsWith("/")) {
                    file = new File(path + "/");
                }
                byte[] fileByte = FileUtils.fileToByte(file);
                if (fileByte != null) {
                    jarFiles.put(file.getName(), fileByte);
                }
            });
            return jarFiles;
        } else {
            return new HashMap<>();
        }
    }

    public Map<String, byte[]> getPlugJar(List<String> pluginIds) {
        Map<String, byte[]> jarFiles = new LinkedHashMap<>();
        PluginExample example = new PluginExample();
        example.createCriteria().andPluginIdIn(pluginIds).andScenarioNotEqualTo(PluginScenario.platform.name());
        List<Plugin> plugins = pluginMapper.selectByExample(example);
        if (CollectionUtils.isNotEmpty(plugins)) {
            plugins = plugins.stream().collect(Collectors.collectingAndThen(Collectors.toCollection(() ->
                    new TreeSet<>(Comparator.comparing(Plugin::getPluginId))), ArrayList::new));
            plugins.forEach(item -> {
                File file = new File(item.getSourcePath());
                if (file.exists() && !file.isDirectory()) {
                    byte[] fileByte = FileUtils.fileToByte(file);
                    if (ArrayUtils.isNotEmpty(fileByte)) {
                        jarFiles.put(file.getName(), fileByte);
                    }
                }
            });

        }
        return jarFiles;
    }

    private String replaceJmx(String jmx) {
        jmx = StringUtils.replace(jmx, "<DubboSample", "<io.github.ningyu.jmeter.plugin.dubbo.sample.DubboSample");
        jmx = StringUtils.replace(jmx, "</DubboSample>", "</io.github.ningyu.jmeter.plugin.dubbo.sample.DubboSample>");
        jmx = StringUtils.replace(jmx, " guiclass=\"DubboSampleGui\" ", " guiclass=\"io.github.ningyu.jmeter.plugin.dubbo.gui.DubboSampleGui\" ");
        jmx = StringUtils.replace(jmx, " guiclass=\"DubboDefaultConfigGui\" ", " guiclass=\"io.github.ningyu.jmeter.plugin.dubbo.gui.DubboDefaultConfigGui\" ");
        jmx = StringUtils.replace(jmx, " testclass=\"DubboSample\" ", " testclass=\"io.github.ningyu.jmeter.plugin.dubbo.sample.DubboSample\" ");
        return jmx;
    }

    private byte[] zipFilesToByteArray(String testId, String reportId, HashTree hashTree) {
        String fileName = testId + ".jmx";

        /*
            v2.7版本修改jmx附件逻辑：
            在node执行机器设置临时文件目录。这里只提供jmx
            node拿到jmx后解析jmx中包含的附件信息。附件通过以下流程来获取：
              1 从node执行机的临时文件夹
              2 临时文件夹中未找到的文件，根据文件类型来判断是否从minio/git下载，然后缓存到临时文件夹。
              3 MinIO、Git中依然未能找到的文件（主要是Local文件），通过主工程下载，然后缓存到临时文件夹
            所以这里不再使用以下方法来获取附件内容
            Map<String, byte[]> multipartFiles = this.getMultipartFiles(testId, hashTree);
            转为解析jmx中附件节点，赋予相关信息(例如文件关联类型、路径、更新时间等),并将文件信息存储在redis中，为了进行连接ms下载时的安全校验
         */
        List<AttachmentBodyFile> attachmentBodyFileList = ApiFileUtil.getExecuteFile(hashTree, reportId, false);
        if (CollectionUtils.isNotEmpty(attachmentBodyFileList)) {
            redisTemplateService.setIfAbsent(JmxFileUtil.getExecuteFileKeyInRedis(reportId), JmxFileUtil.getRedisJmxFileString(attachmentBodyFileList));
        }

        String jmx = new MsTestPlan().getJmx(hashTree);
        // 处理dubbo请求生成jmx文件
        if (StringUtils.isNotEmpty(jmx)) {
            jmx = replaceJmx(jmx);
        }
        Map<String, byte[]> files = new HashMap<>();
        //  每个测试生成一个文件夹
        files.put(fileName, jmx.getBytes(StandardCharsets.UTF_8));

        return listBytesToZip(files);
    }

    private byte[] listBytesToZip(Map<String, byte[]> content) {
        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(byteArrayOutputStream)) {
            for (String key : content.keySet()) {
                ZipEntry entry = new ZipEntry(key);
                entry.setSize(content.get(key).length);
                zos.putNextEntry(entry);
                zos.write(content.get(key));
            }
            zos.closeEntry();
            zos.close();
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            LogUtil.error(e);
            return new byte[0];
        }
    }

    /**
     * 打包ms本地文件
     *
     * @param request
     * @return
     */
    public byte[] zipLocalFilesToByteArray(BodyFileRequest request) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (CollectionUtils.isNotEmpty(request.getBodyFiles())) {
            //获取要下载的合法文件
            List<BodyFile> bodyFiles = this.getLegalFiles(request);
            HashTreeUtil.downFile(bodyFiles, files, fileMetadataService);
            for (BodyFile bodyFile : bodyFiles) {
                File file = new File(bodyFile.getName());
                if (file.exists() && StringUtils.startsWith(file.getPath(), FileUtils.ROOT_DIR)) {
                    byte[] fileByte = FileUtils.fileToByte(file);
                    if (fileByte != null) {
                        files.put(file.getAbsolutePath(), fileByte);
                    }
                }
            }
        }
        Map<String, byte[]> zipFiles = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            //去除文件路径前的body路径
            String filePath = entry.getKey();
            if (StringUtils.startsWith(filePath, FileUtils.BODY_FILE_DIR + "/")) {
                filePath = StringUtils.substring(filePath, FileUtils.BODY_FILE_DIR.length() + 1);
            }
            zipFiles.put(filePath, entry.getValue());
        }
        return listBytesToZip(zipFiles);
    }

    private List<BodyFile> getLegalFiles(BodyFileRequest request) {
        List<BodyFile> returnList = new ArrayList<>();

        Object jmxFileInfoObj = redisTemplateService.get(JmxFileUtil.getExecuteFileKeyInRedis(request.getReportId()));
        List<AttachmentBodyFile> fileInJmx = JmxFileUtil.formatRedisJmxFileString(jmxFileInfoObj);
        redisTemplateService.delete(JmxFileUtil.getExecuteFileKeyInRedis(request.getReportId()));

        if (CollectionUtils.isNotEmpty(request.getBodyFiles())) {
            request.getBodyFiles().forEach(attachmentBodyFile -> {
                for (AttachmentBodyFile jmxFile : fileInJmx) {
                    if (StringUtils.isBlank(attachmentBodyFile.getRefResourceId())) {
                        if (StringUtils.equals(attachmentBodyFile.getName(), jmxFile.getFilePath())
                                && StringUtils.equals(attachmentBodyFile.getName(), jmxFile.getName())) {
                            returnList.add(attachmentBodyFile);
                        }
                    } else {
                        if (StringUtils.equals(attachmentBodyFile.getRefResourceId(), jmxFile.getFileMetadataId())) {
                            returnList.add(attachmentBodyFile);
                        }
                    }
                }
            });
        }
        return returnList;
    }

}
