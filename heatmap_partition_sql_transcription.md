# 热力图分区表 SQL 转写

说明：以下内容根据图片可见部分转写。图片中 `CREATE INDEX ... local (partition ...)` 这几组语句前面能看到 `//`，我按注释状态保留；实际可见执行的是后面的 `alter index ... rebuild`。

## 类头和入口

```java
package com.bocsoft.branch.efficiency.application.locator.service.impl;

import com.bocsoft.branch.efficiency.application.locator.mapper.biz.PublicMapper;
import com.bocsoft.branch.efficiency.application.locator.service.HeatMapPartTableService;
import com.bocsoft.branch.efficiency.application.locator.utils.SqlUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HeatMapPartTableServiceImpl
 *
 * @Author hq
 * @Date 2026/6/2
 */
@Slf4j
@Service
public class HeatMapPartTableServiceImpl implements HeatMapPartTableService {
    @Autowired
    PublicMapper publicMapper;

    //基础标签总表名
    private static final String BASIC_TAG_MASTER_TB_NAME="tb_grid_filter_num_total";
    //场景标签总表名
    private static final String PER_POR_MASTER_TB_NAME="tb_grid_personalized_portrait_total";
    //金融app总表名
    private static final String FINANCE_APP_MASTER_TB_NAME="tb_grid_finance_app_total";
    //房价租金总表名
    private static final String LAND_VALUE_MASTER_TB_NAME="tb_grid_land_value_total";
    //人群画像总表名
    private static final String PER_NUM_MASTER_TB_NAME="tb_grid_permanent_num_total";

    @Override
    public void createHeatMapPartTb(String date, String cityCodes) {
        String[] cityCodesArr = cityCodes.split(",");
        createHeatMapPartTb(date,cityCodesArr);
    }

    /**
     * 创建热力图所需分区表
     * @param date 数据时间
     * @param cityCodes 当前时间生效的城市码
     */
    @Override
    public void createHeatMapPartTb(String date, String[] cityCodes) {
        log.info("当前数据时间: {},共: {}个城市需创建热力分区表",date,cityCodes.length);
        long time=System.currentTimeMillis();
        for (String cityCode : cityCodes) {
            Long batchId = Long.parseLong(date + cityCode);
            //Long batchId = 202603310100L;
            log.info("当前热力分区表批次id:{},开始分区",batchId);
            //基础标签
            basicTagData(date,batchId,cityCode);
            //场景标签
            perPopTagData(date,batchId,cityCode);
            //金融app
            appData(date,batchId,cityCode);
            //房价租金
            landValData(date,batchId,cityCode);
            //人群画像
            perNumData(date,batchId,cityCode);
            log.info("当前热力分区表批次id:{},分区完成",batchId);
        }
        log.info("当前时间: {},共: {}个城市创建热力分区表完成，用时{}s",date,cityCodes.length,(System.currentTimeMillis()-time)/1000);
    }
```

## 人群画像 `perNumData`

```java
    //人群画像
    private void perNumData(String date,Long batchId,String cityCode){
        //分区
        log.info("tb:{},batch:{},开始处理  人群画像分区",PER_NUM_MASTER_TB_NAME,batchId);
        String tablePartSql = SqlUtil.getTablePartSql(PER_NUM_MASTER_TB_NAME, batchId);
        try {
            publicMapper.execUpdate(tablePartSql);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("with existing partition name")) {
                log.info("表：{},分区：{} 已存在",PER_NUM_MASTER_TB_NAME,batchId);
            } else {
                throw e;
            }
        }
        log.info("tb:{},batch:{},  人群画像分区完成",PER_NUM_MASTER_TB_NAME,batchId);

        //分区索引创建
        log.info("tb:{},batch:{}, 开始处理  人群画像分区索引",PER_NUM_MASTER_TB_NAME,batchId);
        String tbName = SqlUtil.getPartTbName(PER_NUM_MASTER_TB_NAME, batchId);
        String sql=
//                "CREATE INDEX idx_perm_"+batchId+"_geom " +
//                "ON "+PER_NUM_MASTER_TB_NAME+" USING GIST (geom_polygon) local (partition "+tbName+");" +
//                "CREATE INDEX idx_perm_"+batchId+"_grid_id " +
//                "ON "+PER_NUM_MASTER_TB_NAME+" (grid_id) local (partition "+tbName+");" +
//                "CREATE INDEX idx_perm_"+batchId+"_code_city " +
//                "ON "+PER_NUM_MASTER_TB_NAME+" (code_city) local (partition "+tbName+");" +
//                "CREATE INDEX idx_perm_"+batchId+"_code_coun " +
//                "ON "+PER_NUM_MASTER_TB_NAME+" (code_coun) local (partition "+tbName+");" +
                "alter index idx_perm_total_geom rebuild;" +
                "alter index idx_perm_total_grid_id rebuild;" +
                "alter index idx_perm_total_code_city rebuild;" +
                "alter index idx_perm_total_code_coun rebuild;";
        publicMapper.execUpdate(sql);
        log.info("tb:{},batch:{},  人群画像分区索引完成",PER_NUM_MASTER_TB_NAME,batchId);

        //数据关联保存
        log.info("tb:{},batch:{},开始  人群画像数据保存",PER_NUM_MASTER_TB_NAME,batchId);
        String insertSql=
                "INSERT INTO "+tbName+" ( " +
                "     batch_id, " +
                "     grid_id, " +
                "     geom_polygon, " +
                "     name_city, " +
                "     code_city, " +
                "     name_coun, " +
                "     code_coun, " +
                "     population_type, " +
                "     num " +
                ") " +
                "SELECT " +
                "     "+batchId+" AS batch_id, " +
                "     g.grid_id, " +
                "     g.geom_polygon, " +
                "     g.name_city, " +
                "     g.code_city, " +
                "     g.name_coun, " +
                "     g.code_coun, " +
                "     n.population_type, " +
                "     n.num " +
                "FROM tb_grid g " +
                "INNER JOIN tb_grid_permanent_num n ON g.grid_id = n.grid_id " +
                "WHERE g.code_city="+cityCode+" AND n.date='"+date+"' AND n.population_type IN ('HOME','WORK');";
        int i = publicMapper.execInsert(insertSql);
        log.info("tb:{},batch:{},  人群画像数据保存完成,共: {}条",PER_NUM_MASTER_TB_NAME,batchId,i);
    }
```

## 房价租金 `landValData`

```java
    //房价租金
    private void landValData(String date,Long batchId,String cityCode){
        //分区
        log.info("tb:{},batch:{},开始处理 房价租金分区",LAND_VALUE_MASTER_TB_NAME,batchId);
        String tablePartSql = SqlUtil.getTablePartSql(LAND_VALUE_MASTER_TB_NAME, batchId);
        try {
            publicMapper.execUpdate(tablePartSql);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("with existing partition name")) {
                log.info("表：{},分区：{} 已存在",LAND_VALUE_MASTER_TB_NAME,batchId);
            } else {
                throw e;
            }
        }
        log.info("tb:{},batch:{}, 房价租金分区完成",LAND_VALUE_MASTER_TB_NAME,batchId);

        //分区索引创建
        log.info("tb:{},batch:{},开始处理 房价租金分区索引",LAND_VALUE_MASTER_TB_NAME,batchId);
        String tbName = SqlUtil.getPartTbName(LAND_VALUE_MASTER_TB_NAME, batchId);
        String sql=
//                "CREATE INDEX idx_land_"+batchId+"_geom " +
//                "ON "+LAND_VALUE_MASTER_TB_NAME+" USING GIST (geom_polygon) local (partition "+tbName+");" +
//                "CREATE INDEX idx_land_"+batchId+"_grid_id " +
//                "ON "+LAND_VALUE_MASTER_TB_NAME+" (grid_id) local (partition "+tbName+");" +
//                "CREATE INDEX idx_land_"+batchId+"_code_city " +
//                "ON "+LAND_VALUE_MASTER_TB_NAME+" (code_city) local (partition "+tbName+");" +
//                "CREATE INDEX idx_land_"+batchId+"_code_coun " +
//                "ON "+LAND_VALUE_MASTER_TB_NAME+" (code_coun) local (partition "+tbName+");" +
                "alter index idx_land_total_geom rebuild;" +
                "alter index idx_land_total_grid_id rebuild;" +
                "alter index idx_land_total_code_city rebuild;" +
                "alter index idx_land_total_code_coun rebuild;";
        publicMapper.execUpdate(sql);
        log.info("tb:{},batch:{}, 房价租金分区索引完成",LAND_VALUE_MASTER_TB_NAME,batchId);

        //数据关联保存
        log.info("tb:{},batch:{},开始 房价租金数据保存",LAND_VALUE_MASTER_TB_NAME,batchId);
        String insertSql=
                "INSERT INTO "+tbName+" ( " +
                "     batch_id, " +
                "     grid_id, " +
                "     geom_polygon, " +
                "     name_city, " +
                "     code_city, " +
                "     name_coun, " +
                "     code_coun, " +
                "     average_rent, " +
                "     average_house_price " +
                ") " +
                "SELECT " +
                "     "+batchId+" AS batch_id, " +
                "     g.grid_id, " +
                "     g.geom_polygon, " +
                "     g.name_city, " +
                "     g.code_city, " +
                "     g.name_coun, " +
                "     g.code_coun, " +
                "     COALESCE(n.average_rent, 0) AS average_rent, " +
                "     COALESCE(n.average_house_price, 0) AS average_house_price " +
                "FROM tb_grid g " +
                "INNER JOIN tb_grid_land_value n ON g.grid_id = n.grid_id " +
                "WHERE g.code_city="+cityCode+" AND n.date='"+date+"';";
        int i = publicMapper.execInsert(insertSql);
        log.info("tb:{},batch:{}, 房价租金数据保存完成,共: {}条",LAND_VALUE_MASTER_TB_NAME,batchId,i);
    }
```

## 金融 app `appData`

```java
    //金融app
    private void appData(String date,Long batchId,String cityCode){
        //分区
        log.info("tb:{},batch:{},开始处理 金融app分区",FINANCE_APP_MASTER_TB_NAME,batchId);
        String tablePartSql = SqlUtil.getTablePartSql(FINANCE_APP_MASTER_TB_NAME, batchId);
        try {
            publicMapper.execUpdate(tablePartSql);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("with existing partition name")) {
                log.info("表：{},分区：{} 已存在",FINANCE_APP_MASTER_TB_NAME,batchId);
            } else {
                throw e;
            }
        }
        log.info("tb:{},batch:{}, 金融app分区完成",FINANCE_APP_MASTER_TB_NAME,batchId);

        //分区索引创建
        log.info("tb:{},batch:{},开始处理 金融app分区索引",FINANCE_APP_MASTER_TB_NAME,batchId);
        String tbName = SqlUtil.getPartTbName(FINANCE_APP_MASTER_TB_NAME, batchId);
        String sql=
//                "CREATE INDEX idx_app_"+batchId+"_geom " +
//                "ON "+FINANCE_APP_MASTER_TB_NAME+" USING GIST (geom_polygon) local (partition "+tbName+");" +
//                "CREATE INDEX idx_app_"+batchId+"_grid_id " +
//                "ON "+FINANCE_APP_MASTER_TB_NAME+" (grid_id) local (partition "+tbName+");" +
//                "CREATE INDEX idx_app_"+batchId+"_code_city " +
//                "ON "+FINANCE_APP_MASTER_TB_NAME+" (code_city) local (partition "+tbName+");" +
//                "CREATE INDEX idx_app_"+batchId+"_code_coun " +
//                "ON "+FINANCE_APP_MASTER_TB_NAME+" (code_coun) local (partition "+tbName+");" +
                "alter index idx_app_total_geom rebuild;" +
                "alter index idx_app_total_grid_id rebuild;" +
                "alter index idx_app_total_code_city rebuild;" +
                "alter index idx_app_total_code_coun rebuild;";
        publicMapper.execUpdate(sql);
        log.info("tb:{},batch:{}, 金融app分区索引完成",FINANCE_APP_MASTER_TB_NAME,batchId);

        //数据关联保存
        log.info("tb:{},batch:{},开始 金融app数据保存",FINANCE_APP_MASTER_TB_NAME,batchId);
        String insertSql=
                "INSERT INTO "+tbName+" ( " +
                "     batch_id, grid_id, geom_polygon, name_city, code_city, name_coun, code_coun, " +
                "     debit_card_bc, debit_card_abc, debit_card_icbc, debit_card_cbc, debit_card_cmbc, " +
                "     debit_card_cmsb, debit_card_pab, debit_card_spdb, debit_card_cib, debit_card_ccb, " +
                "     debit_card_ceb, debit_card_hxb, debit_card_psbc, credit_card_bocom, debit_card_bocom, " +
                "     credit_card_cmbc, debit_card_bosc, debit_card_crcb " +
                ") " +
                "SELECT " +
                "     "+batchId+" AS batch_id, g.grid_id, g.geom_polygon, g.name_city, g.code_city, g.name_coun, g.code_coun, " +
                "     COALESCE(SUM(n.debit_card_bc), 0) AS debit_card_bc, " +
                "     COALESCE(SUM(n.debit_card_abc), 0) AS debit_card_abc, " +
                "     COALESCE(SUM(n.debit_card_icbc), 0) AS debit_card_icbc, " +
                "     COALESCE(SUM(n.debit_card_cbc), 0) AS debit_card_cbc, " +
                "     COALESCE(SUM(n.debit_card_cmbc), 0) AS debit_card_cmbc, " +
                "     COALESCE(SUM(n.debit_card_cmsb), 0) AS debit_card_cmsb, " +
                "     COALESCE(SUM(n.debit_card_pab), 0) AS debit_card_pab, " +
                "     COALESCE(SUM(n.debit_card_spdb), 0) AS debit_card_spdb, " +
                "     COALESCE(SUM(n.debit_card_cib), 0) AS debit_card_cib, " +
                "     COALESCE(SUM(n.debit_card_ccb), 0) AS debit_card_ccb, " +
                "     COALESCE(SUM(n.debit_card_ceb), 0) AS debit_card_ceb, " +
                "     COALESCE(SUM(n.debit_card_hxb), 0) AS debit_card_hxb, " +
                "     COALESCE(SUM(n.debit_card_psbc), 0) AS debit_card_psbc, " +
                "     COALESCE(SUM(n.credit_card_bocom), 0) AS credit_card_bocom, " +
                "     COALESCE(SUM(n.debit_card_bocom), 0) AS debit_card_bocom, " +
                "     COALESCE(SUM(n.credit_card_cmbc), 0) AS credit_card_cmbc, " +
                "     COALESCE(SUM(n.debit_card_bosc), 0) AS debit_card_bosc, " +
                "     COALESCE(SUM(n.debit_card_crcb), 0) AS debit_card_crcb " +
                "FROM tb_grid g " +
                "INNER JOIN tb_grid_finance_app n ON g.grid_id = n.grid_id " +
                "WHERE g.code_city="+cityCode+" AND n.date='"+date+"' AND n.population_type IN ('HOME','WORK') " +
                "GROUP BY g.grid_id, g.geom_polygon, g.name_city, g.code_city, g.name_coun, g.code_coun;";
        int i = publicMapper.execInsert(insertSql);
        log.info("tb:{},batch:{}, 金融app数据保存完成,共: {}条",FINANCE_APP_MASTER_TB_NAME,batchId,i);
    }
```

## 场景标签 `perPopTagData`

```java
    //场景标签
    private void perPopTagData(String date,Long batchId,String cityCode){
        //分区
        log.info("tb:{},batch:{},开始处理场景标签分区",PER_POR_MASTER_TB_NAME,batchId);
        String tablePartSql = SqlUtil.getTablePartSql(PER_POR_MASTER_TB_NAME, batchId);
        try {
            publicMapper.execUpdate(tablePartSql);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("with existing partition name")) {
                log.info("表：{},分区：{} 已存在",PER_POR_MASTER_TB_NAME,batchId);
            } else {
                throw e;
            }
        }
        log.info("tb:{},batch:{},场景标签分区完成",PER_POR_MASTER_TB_NAME,batchId);

        //分区索引创建
        log.info("tb:{},batch:{},开始处理场景标签分区索引",PER_POR_MASTER_TB_NAME,batchId);
        String tbName = SqlUtil.getPartTbName(PER_POR_MASTER_TB_NAME, batchId);
        String sql=
//                "CREATE INDEX idx_pp_"+batchId+"_geom " +
//                "ON "+PER_POR_MASTER_TB_NAME+" USING GIST (geom_polygon) local (partition "+tbName+");" +
//                "CREATE INDEX idx_pp_"+batchId+"_grid_id " +
//                "ON "+PER_POR_MASTER_TB_NAME+" (grid_id) local (partition "+tbName+");" +
//                "CREATE INDEX idx_pp_"+batchId+"_code_city " +
//                "ON "+PER_POR_MASTER_TB_NAME+" (code_city) local (partition "+tbName+");" +
//                "CREATE INDEX idx_pp_"+batchId+"_code_coun " +
//                "ON "+PER_POR_MASTER_TB_NAME+" (code_coun) local (partition "+tbName+");" +
                "alter index idx_pp_total_geom rebuild;" +
                "alter index idx_pp_total_grid_id rebuild;" +
                "alter index idx_pp_total_code_city rebuild;" +
                "alter index idx_pp_total_code_coun rebuild;";
        publicMapper.execUpdate(sql);
        log.info("tb:{},batch:{},场景标签分区索引完成",PER_POR_MASTER_TB_NAME,batchId);

        //数据关联保存
        log.info("tb:{},batch:{},开始场景标签数据保存",PER_POR_MASTER_TB_NAME,batchId);
        String insertSql=
                "INSERT INTO "+tbName+" ( " +
                "     batch_id, grid_id, geom_polygon, name_city, code_city, name_coun, code_coun, " +
                "     hieg_end_individual, college_student, white_collar, senior_middle_class, " +
                "     flexible_employment, millennials, financial_management, quinguagenarian, " +
                "     urban_silver_haired, blue_collar, homebound_population, loyalty_data_high, " +
                "     loyalty_data_mid, loyalty_data_low, maternity, shopping, fashion_icon, wool, " +
                "     business_traveler, workaholic, \"3c\", game_animation, travel_expert, finance, " +
                "     medical_staff, servant_public_institution, white_collar_general_staff, " +
                "     worker_service_staff, teacher, farmer_other, want_to_decorate, want_to_car, " +
                "     have_children, there_room " +
                ") " +
                "SELECT " +
                "     "+batchId+" AS batch_id, g.grid_id, g.geom_polygon, g.name_city, g.code_city, g.name_coun, g.code_coun, " +
                "     COALESCE(SUM(n.hieg_end_individual), 0) AS hieg_end_individual, " +
                "     COALESCE(SUM(n.college_student), 0) AS college_student, " +
                "     COALESCE(SUM(n.white_collar), 0) AS white_collar, " +
                "     COALESCE(SUM(n.senior_middle_class), 0) AS senior_middle_class, " +
                "     COALESCE(SUM(n.flexible_employment), 0) AS flexible_employment, " +
                "     COALESCE(SUM(n.millennials), 0) AS millennials, " +
                "     COALESCE(SUM(n.financial_management), 0) AS financial_management, " +
                "     COALESCE(SUM(n.quinguagenarian), 0) AS quinguagenarian, " +
                "     COALESCE(SUM(n.urban_silver_haired), 0) AS urban_silver_haired, " +
                "     COALESCE(SUM(n.blue_collar), 0) AS blue_collar, " +
                "     COALESCE(SUM(n.homebound_population), 0) AS homebound_population, " +
                "     COALESCE(SUM(n.loyalty_data_high), 0) AS loyalty_data_high, " +
                "     COALESCE(SUM(n.loyalty_data_mid), 0) AS loyalty_data_mid, " +
                "     COALESCE(SUM(n.loyalty_data_low), 0) AS loyalty_data_low, " +
                "     COALESCE(SUM(n.maternity), 0) AS maternity, " +
                "     COALESCE(SUM(n.shopping), 0) AS shopping, " +
                "     COALESCE(SUM(n.fashion_icon), 0) AS fashion_icon, " +
                "     COALESCE(SUM(n.wool), 0) AS wool, " +
                "     COALESCE(SUM(n.business_traveler), 0) AS business_traveler, " +
                "     COALESCE(SUM(n.workaholic), 0) AS workaholic, " +
                "     COALESCE(SUM(n.\"3c\"), 0) AS \"3c\", " +
                "     COALESCE(SUM(n.game_animation), 0) AS game_animation, " +
                "     COALESCE(SUM(n.travel_expert), 0) AS travel_expert, " +
                "     COALESCE(SUM(n.finance), 0) AS finance, " +
                "     COALESCE(SUM(n.medical_staff), 0) AS medical_staff, " +
                "     COALESCE(SUM(n.servant_public_institution), 0) AS servant_public_institution, " +
                "     COALESCE(SUM(n.white_collar_general_staff), 0) AS white_collar_general_staff, " +
                "     COALESCE(SUM(n.worker_service_staff), 0) AS worker_service_staff, " +
                "     COALESCE(SUM(n.teacher), 0) AS teacher, " +
                "     COALESCE(SUM(n.farmer_other), 0) AS farmer_other, " +
                "     COALESCE(SUM(n.want_to_decorate), 0) AS want_to_decorate, " +
                "     COALESCE(SUM(n.want_to_car), 0) AS want_to_car, " +
                "     COALESCE(SUM(n.have_children), 0) AS have_children, " +
                "     COALESCE(SUM(n.there_room), 0) AS there_room " +
                "FROM tb_grid g " +
                "JOIN tb_grid_personalized_portrait n ON g.grid_id = n.grid_id " +
                "WHERE g.code_city="+cityCode+" AND n.date='"+date+"' AND n.population_type IN ('HOME','WORK') " +
                "GROUP BY g.grid_id, g.geom_polygon, g.name_city, g.code_city, g.name_coun, g.code_coun;";
        int i = publicMapper.execInsert(insertSql);
        log.info("tb:{},batch:{},场景标签数据保存完成,共: {}条",PER_POR_MASTER_TB_NAME,batchId,i);
    }
```

## 基础标签 `basicTagData` 开头

图片最后只截到这个方法开头，后续 SQL 没有出现在当前图片里。

```java
    //基础标签
    private void basicTagData(String date,Long batchId,String cityCode){
        //分区
        log.info("tb:{},batch:{},开始处理基础标签分区",BASIC_TAG_MASTER_TB_NAME,batchId);
        String tablePartSql = SqlUtil.getTablePartSql(BASIC_TAG_MASTER_TB_NAME,batchId);
        try {
            publicMapper.execUpdate(tablePartSql);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("with existing partition name")) {
                log.info("表：{},分区：{} 已存在",BASIC_TAG_MASTER_TB_NAME,batchId);
            } else {
                throw e;
```
