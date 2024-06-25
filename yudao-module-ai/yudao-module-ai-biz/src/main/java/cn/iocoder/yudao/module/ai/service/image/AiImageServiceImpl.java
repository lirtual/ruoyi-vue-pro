package cn.iocoder.yudao.module.ai.service.image;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpUtil;
import cn.iocoder.yudao.framework.ai.core.enums.AiPlatformEnum;
import cn.iocoder.yudao.framework.ai.core.model.midjourney.api.MidjourneyApi;
import cn.iocoder.yudao.framework.common.pojo.PageParam;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.module.ai.controller.admin.image.vo.AiImageDrawReqVO;
import cn.iocoder.yudao.module.ai.controller.admin.image.vo.midjourney.AiImageMidjourneyImagineReqVO;
import cn.iocoder.yudao.module.ai.dal.dataobject.image.AiImageDO;
import cn.iocoder.yudao.module.ai.dal.mysql.image.AiImageMapper;
import cn.iocoder.yudao.module.ai.enums.image.AiImageStatusEnum;
import cn.iocoder.yudao.module.ai.service.model.AiApiKeyService;
import cn.iocoder.yudao.module.infra.api.file.FileApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.ImageClient;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.stabilityai.api.StabilityAiImageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;
import static cn.iocoder.yudao.module.ai.enums.ErrorCodeConstants.*;

/**
 * AI 绘画 Service 实现类
 *
 * @author fansili
 */
@Service
@Slf4j
public class AiImageServiceImpl implements AiImageService {

    @Resource
    private AiImageMapper imageMapper;

    @Resource
    private FileApi fileApi;

    @Resource
    private AiApiKeyService apiKeyService;

    @Resource
    private MidjourneyApi midjourneyApi;

    @Value("${ai.midjourney-proxy.notifyUrl:http://127.0.0.1:48080/admin-api/ai/image/midjourney-notify}")
    private String midjourneyNotifyUrl;

    @Override
    public PageResult<AiImageDO> getImagePageMy(Long userId, PageParam pageReqVO) {
        return imageMapper.selectPage(userId, pageReqVO);
    }

    @Override
    public AiImageDO getImage(Long id) {
        return imageMapper.selectById(id);
    }

    @Override
    public Long drawImage(Long userId, AiImageDrawReqVO drawReqVO) {
        // 1. 保存数据库
        AiImageDO image = BeanUtils.toBean(drawReqVO, AiImageDO.class).setUserId(userId).setPublicStatus(false)
                .setStatus(AiImageStatusEnum.IN_PROGRESS.getStatus());
        imageMapper.insert(image);
        // 2. 异步绘制，后续前端通过返回的 id 进行轮询结果
        getSelf().executeDrawImage(image, drawReqVO);
        return image.getId();
    }

    @Async
    public void executeDrawImage(AiImageDO image, AiImageDrawReqVO req) {
        try {
            // 1.1 构建请求
            ImageOptions request = buildImageOptions(req);
            // 1.2 执行请求
            ImageClient imageClient = apiKeyService.getImageClient(AiPlatformEnum.validatePlatform(req.getPlatform()));
            ImageResponse response = imageClient.call(new ImagePrompt(req.getPrompt(), request));

            // 2. 上传到文件服务
            byte[] fileContent = Base64.decode(response.getResult().getOutput().getB64Json());
            String filePath = fileApi.createFile(fileContent);

            // 3. 更新数据库
            imageMapper.updateById(new AiImageDO().setId(image.getId()).setStatus(AiImageStatusEnum.SUCCESS.getStatus())
                    .setPicUrl(filePath));
        } catch (Exception ex) {
            log.error("[doDall][image({}) 生成异常]", image, ex);
            imageMapper.updateById(new AiImageDO().setId(image.getId())
                    .setStatus(AiImageStatusEnum.FAIL.getStatus()).setErrorMessage(ex.getMessage()));
        }
    }

    private static ImageOptions buildImageOptions(AiImageDrawReqVO draw) {
        if (ObjUtil.equal(draw.getPlatform(), AiPlatformEnum.OPENAI.getPlatform())) {
            // https://platform.openai.com/docs/api-reference/images/create
            return OpenAiImageOptions.builder().withModel(draw.getModel())
                    .withHeight(draw.getHeight()).withWidth(draw.getWidth())
                    .withStyle(MapUtil.getStr(draw.getOptions(), "style")) // 风格
                    .withResponseFormat("b64_json")
                    .build();
        } else if (ObjUtil.equal(draw.getPlatform(), AiPlatformEnum.STABLE_DIFFUSION.getPlatform())) {
            // https://platform.stability.ai/docs/api-reference#tag/Text-to-Image/operation/textToImage
            return StabilityAiImageOptions.builder().withModel(draw.getModel())
                    .withHeight(draw.getHeight()).withWidth(draw.getWidth()) // TODO @芋艿：各种参数
                    .build();
        }
        throw new IllegalArgumentException("不支持的 AI 平台：" + draw.getPlatform());
    }

    @Override
    public void deleteImageMy(Long id, Long userId) {
        // 1. 校验是否存在
        AiImageDO image = validateImageExists(id);
        if (ObjUtil.notEqual(image.getUserId(), userId)) {
            throw exception(AI_IMAGE_NOT_EXISTS);
        }
        // 2. 删除记录
        imageMapper.deleteById(id);
    }

    private AiImageDO validateImageExists(Long id) {
        AiImageDO image = imageMapper.selectById(id);
        if (image == null) {
            throw exception(AI_IMAGE_NOT_EXISTS);
        }
        return image;
    }

    // ================ midjourney 专属 ================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long midjourneyImagine(Long userId, AiImageMidjourneyImagineReqVO reqVO) {
        // 1. 保存数据库
        AiImageDO image = BeanUtils.toBean(reqVO, AiImageDO.class).setUserId(userId).setPublicStatus(false)
                .setStatus(AiImageStatusEnum.IN_PROGRESS.getStatus())
                .setPlatform(AiPlatformEnum.MIDJOURNEY.getPlatform());
        imageMapper.insert(image);

        // 2. 调用 Midjourney Proxy 提交任务
        MidjourneyApi.ImagineRequest imagineRequest = new MidjourneyApi.ImagineRequest(
                null, midjourneyNotifyUrl, reqVO.getPrompt(),
                MidjourneyApi.ImagineRequest.buildState(reqVO.getWidth(), reqVO.getHeight(), reqVO.getVersion(), reqVO.getModel()));
        MidjourneyApi.SubmitResponse imagineResponse = midjourneyApi.imagine(imagineRequest);

        // 3. 情况一【失败】：抛出业务异常
        if (!MidjourneyApi.SubmitCodeEnum.SUCCESS_CODES.contains(imagineResponse.code())) {
            String description = imagineResponse.description().contains("quota_not_enough") ?
                    "账户余额不足" : imagineResponse.description();
            throw exception(AI_IMAGE_MIDJOURNEY_SUBMIT_FAIL, description);
        }

        // 4. 情况二【成功】：更新 taskId 和参数
        imageMapper.updateById(new AiImageDO()
                .setId(image.getId())
                .setTaskId(imagineResponse.result())
                .setOptions(BeanUtil.beanToMap(reqVO))
        );
        return image.getId();
    }

    @Override
    public Integer midjourneySync() {
        // 1.1 获取 Midjourney 平台，状态在 “进行中” 的 image
        List<AiImageDO> imageList = imageMapper.selectListByStatusAndPlatform(
                AiImageStatusEnum.IN_PROGRESS.getStatus(), AiPlatformEnum.MIDJOURNEY.getPlatform());
        if (CollUtil.isEmpty(imageList)) {
            return 0;
        }
        // 1.2 调用 Midjourney Proxy 获取任务进展
        List<MidjourneyApi.Notify> taskList = midjourneyApi.getTaskList(convertSet(imageList, AiImageDO::getTaskId));
        Map<String, MidjourneyApi.Notify> taskMap = convertMap(taskList, MidjourneyApi.Notify::id);

        // 2. 逐个处理，更新进展
        int count = 0;
        for (AiImageDO image : imageList) {
            MidjourneyApi.Notify notify = taskMap.get(image.getTaskId());
            if (notify == null) {
                log.error("[midjourneySync][image({}) 查询不到进展]", image);
                continue;
            }
            count++;
            updateMidjourneyStatus(image, notify);
        }
        return count;
    }

    @Override
    public void midjourneyNotify(MidjourneyApi.Notify notify) {
        // 1. 校验 image 存在
        AiImageDO image = imageMapper.selectByTaskId(notify.id());
        if (image == null) {
            log.warn("[midjourneyNotify][回调任务({}) 不存在]", notify.id());
            return;
        }
        // 2. 更新状态
        updateMidjourneyStatus(image, notify);
    }

    private void updateMidjourneyStatus(AiImageDO image, MidjourneyApi.Notify notify) {
        // 1. 转换状态
        Integer status = null;
        if (StrUtil.isNotBlank(notify.status())) {
            MidjourneyApi.TaskStatusEnum taskStatusEnum = MidjourneyApi.TaskStatusEnum.valueOf(notify.status());
            if (MidjourneyApi.TaskStatusEnum.SUCCESS == taskStatusEnum) {
                status = AiImageStatusEnum.SUCCESS.getStatus();
            } else if (MidjourneyApi.TaskStatusEnum.FAILURE == taskStatusEnum) {
                status = AiImageStatusEnum.FAIL.getStatus();
            }
        }

        // 2. 上传图片
        String picUrl = null;
        if (StrUtil.isNotBlank(notify.imageUrl())) {
            try {
                picUrl = fileApi.createFile(HttpUtil.downloadBytes(notify.imageUrl()));
            } catch (Exception e) {
                picUrl = notify.imageUrl();
                log.warn("[updateMidjourneyStatus][图片({}) 地址({}) 上传失败]", image.getId(), notify.imageUrl(), e);
            }
        }

        // 3. 更新 image 状态
        imageMapper.updateById(new AiImageDO().setId(image.getId()).setStatus(status)
                .setPicUrl(picUrl).setButtons(notify.buttons()).setErrorMessage(notify.failReason()));
    }

    @Override
    public void midjourneyAction(Long loginUserId, Long imageId, String customId) {
        // 1、检查 image
        AiImageDO image = validateImageExists(imageId);
        // 2、检查 customId
        validateCustomId(customId, image.getButtons());

        // 3、调用 midjourney proxy
        MidjourneyApi.SubmitResponse submitResponse = midjourneyApi.action(
                new MidjourneyApi.ActionRequest(customId, image.getTaskId(), midjourneyNotifyUrl));
        // 4、检查错误 code (状态码: 1(提交成功), 21(已存在), 22(排队中), other(错误))
        if (!MidjourneyApi.SubmitCodeEnum.SUCCESS_CODES.contains(submitResponse.code())) {
            throw exception(AI_IMAGE_MIDJOURNEY_SUBMIT_FAIL, submitResponse.description());
        }

        // 5、新增 image 记录(根据 image 新增一个)
        AiImageDO newImage = new AiImageDO();
        newImage.setUserId(image.getUserId());
        newImage.setPrompt(image.getPrompt());

        newImage.setPlatform(image.getPlatform());
        newImage.setModel(image.getModel());
        newImage.setWidth(image.getWidth());
        newImage.setHeight(image.getHeight());

        newImage.setStatus(AiImageStatusEnum.IN_PROGRESS.getStatus());
        newImage.setPublicStatus(image.getPublicStatus());

        newImage.setOptions(image.getOptions());
        newImage.setTaskId(submitResponse.result());
        imageMapper.insert(newImage);
    }

    private static void validateCustomId(String customId, List<MidjourneyApi.Button> buttons) {
        boolean isTrue = false;
        for (MidjourneyApi.Button button : buttons) {
            if (button.customId().equals(customId)) {
                isTrue = true;
                break;
            }
        }
        if (!isTrue) {
            throw exception(AI_IMAGE_CUSTOM_ID_NOT_EXISTS);
        }
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private AiImageServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
