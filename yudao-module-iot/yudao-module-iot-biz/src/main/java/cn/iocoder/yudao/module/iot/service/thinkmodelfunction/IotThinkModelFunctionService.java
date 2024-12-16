package cn.iocoder.yudao.module.iot.service.thinkmodelfunction;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.iot.controller.admin.productthingmodel.vo.IotThinkModelFunctionPageReqVO;
import cn.iocoder.yudao.module.iot.controller.admin.productthingmodel.vo.IotThinkModelFunctionSaveReqVO;
import cn.iocoder.yudao.module.iot.dal.dataobject.productthingmodel.IotProductThingModelDO;
import jakarta.validation.Valid;

import java.util.List;

/**
 * IoT 产品物模型 Service 接口
 *
 * @author 芋道源码
 */
public interface IotThinkModelFunctionService {

    /**
     * 创建产品物模型
     *
     * @param createReqVO 创建信息
     * @return 编号
     */
    Long createThinkModelFunction(@Valid IotThinkModelFunctionSaveReqVO createReqVO);


    /**
     * 更新产品物模型
     *
     * @param updateReqVO 更新信息
     */
    void updateThinkModelFunction(@Valid IotThinkModelFunctionSaveReqVO updateReqVO);

    /**
     * 删除产品物模型
     *
     * @param id 编号
     */
    void deleteThinkModelFunction(Long id);

    /**
     * 获得产品物模型
     *
     * @param id 编号
     * @return 产品物模型
     */
    IotProductThingModelDO getThinkModelFunction(Long id);

    /**
     * 获得产品物模型列表
     *
     * @param productId 产品编号
     * @return 产品物模型列表
     */
    List<IotProductThingModelDO> getThinkModelFunctionListByProductId(Long productId);

    /**
     * 获得产品物模型分页
     *
     * @param pageReqVO 分页查询
     * @return 产品物模型分页
     */
    PageResult<IotProductThingModelDO> getThinkModelFunctionPage(IotThinkModelFunctionPageReqVO pageReqVO);

    /**
     * 创建超级表数据模型
     *
     * @param productId 产品编号
     */
    void createSuperTableDataModel(Long productId);

    /**
     * 获得产品物模型列表
     *
     * @param productKey 产品 Key
     * @return 产品物模型列表
     */
    List<IotProductThingModelDO> getThinkModelFunctionListByProductKey(String productKey);
}