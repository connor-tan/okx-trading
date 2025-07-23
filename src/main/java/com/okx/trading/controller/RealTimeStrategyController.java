package com.okx.trading.controller;


import com.okx.trading.adapter.CandlestickBarSeriesConverter;
import com.okx.trading.model.entity.RealTimeOrderEntity;
import com.okx.trading.model.entity.RealTimeStrategyEntity;
import com.okx.trading.model.entity.StrategyInfoEntity;
import com.okx.trading.service.*;
import com.okx.trading.service.impl.*;
import com.okx.trading.strategy.RealTimeStrategyManager;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static com.okx.trading.constant.IndicatorInfo.RUNNING;
import static com.okx.trading.constant.IndicatorInfo.BUY;
import com.okx.trading.model.market.Ticker;
import java.time.Duration;

/**
 * 实时运行策略控制器
 * 提供实时策略的CRUD操作和状态管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/real-time-strategy")
@Tag(name = "实时运行策略管理")
public class RealTimeStrategyController {

    private final HistoricalDataService historicalDataService;
    private final Ta4jBacktestService ta4jBacktestService;
    private final BacktestTradeService backtestTradeService;
    private final MarketDataService marketDataService;
    private final StrategyInfoService strategyInfoService;
    private final DeepSeekApiService deepSeekApiService;
    private final DynamicStrategyService dynamicStrategyService;
    private final JavaCompilerDynamicStrategyService javaCompilerDynamicStrategyService;
    private final SmartDynamicStrategyService smartDynamicStrategyService;
    private final StrategyConversationService strategyConversationService;
    private final CandlestickBarSeriesConverter barSeriesConverter;
    private final RealTimeOrderService realTimeOrderService;
    private final KlineCacheService klineCacheService;
    private final OkxApiService okxApiService;
    private final TradeController tradeController;
    private final RealTimeStrategyManager realTimeStrategyManager;
    private final RealTimeStrategyService realTimeStrategyService;
    private final ExecutorService realTimeTradeScheduler;
    private final ExecutorService scheduler;

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    public RealTimeStrategyController(HistoricalDataService historicalDataService,
                                      Ta4jBacktestService ta4jBacktestService,
                                      BacktestTradeService backtestTradeService,
                                      MarketDataService marketDataService,
                                      StrategyInfoService strategyInfoService,
                                      DeepSeekApiService deepSeekApiService,
                                      DynamicStrategyService dynamicStrategyService,
                                      JavaCompilerDynamicStrategyService javaCompilerDynamicStrategyService,
                                      SmartDynamicStrategyService smartDynamicStrategyService,
                                      StrategyConversationService strategyConversationService,
                                      CandlestickBarSeriesConverter barSeriesConverter,
                                      RealTimeOrderService realTimeOrderService,
                                      KlineCacheService klineCacheService,
                                      OkxApiService okxApiService,
                                      TradeController tradeController,
                                      RealTimeStrategyManager realTimeStrategyManager,
                                      RealTimeStrategyService realTimeStrategyService,
                                      @Qualifier("tradeIndicatorCalculateScheduler") ExecutorService scheduler,
                                      @Qualifier("realTimeTradeIndicatorCalculateScheduler") ExecutorService realTimeTradeScheduler) {
        this.historicalDataService = historicalDataService;
        this.ta4jBacktestService = ta4jBacktestService;
        this.backtestTradeService = backtestTradeService;
        this.marketDataService = marketDataService;
        this.strategyInfoService = strategyInfoService;
        this.deepSeekApiService = deepSeekApiService;
        this.dynamicStrategyService = dynamicStrategyService;
        this.javaCompilerDynamicStrategyService = javaCompilerDynamicStrategyService;
        this.smartDynamicStrategyService = smartDynamicStrategyService;
        this.strategyConversationService = strategyConversationService;
        this.barSeriesConverter = barSeriesConverter;
        this.realTimeOrderService = realTimeOrderService;
        this.klineCacheService = klineCacheService;
        this.okxApiService = okxApiService;
        this.tradeController = tradeController;
        this.realTimeStrategyManager = realTimeStrategyManager;
        this.realTimeStrategyService = realTimeStrategyService;
        this.scheduler = scheduler;
        this.realTimeTradeScheduler = realTimeTradeScheduler;
    }


    /**
     * 实时策略回测接口
     * 获取实时K线数据和历史300根K线数据进行策略回测
     * 当触发交易信号时，实时调用交易接口创建订单
     */
    @PostMapping("/real-time")
    @Operation(summary = "实时策略回测", description = "基于实时K线数据进行策略回测，触发信号时自动下单")
    public com.okx.trading.model.common.ApiResponse<Map<String, Object>> realTimeBacktest(
            @Parameter(name = "策略代码", required = true, example = "STOCHASTIC") @RequestParam String strategyCode,
            @Parameter(name = "交易对", required = true, example = "BTC-USDT") @RequestParam String symbol,
            @Parameter(name = "时间间隔", required = true, example = "1D") @RequestParam String interval,
            @Parameter(name = "交易金额", required = false, example = "20") @RequestParam(required = true) BigDecimal tradeAmount) {
        try {
            if (!realTimeStrategyManager.isLoadedStrategies()) {
                return com.okx.trading.model.common.ApiResponse.error(500, "策略未加载完成，请稍后再试");
            }

            log.info("开始实时策略回测: strategyCode={}, symbol={}, interval={}", strategyCode, symbol, interval);
            LocalDateTime now = LocalDateTime.now();
            // 1. 验证策略是否存在
            Optional<StrategyInfoEntity> strategyOpt = strategyInfoService.getStrategyByCode(strategyCode);
            if (!strategyOpt.isPresent()) {
                return com.okx.trading.model.common.ApiResponse.error(404, "策略不存在: " + strategyCode);
            }
            StrategyInfoEntity strategy = strategyOpt.get();
            RealTimeStrategyEntity realTimeStrategy = new RealTimeStrategyEntity(strategyCode, symbol, interval, now, tradeAmount.doubleValue(), strategy.getStrategyName());
            realTimeStrategy.setStatus(RUNNING);
            realTimeStrategy.setIsActive(true);
            // 策略已经入库， k线订阅
            Map<String, Object> createStrategyResponse = realTimeStrategyManager.startExecuteRealTimeStrategy(realTimeStrategy);
            // 6. 返回初始状态
            return com.okx.trading.model.common.ApiResponse.success(createStrategyResponse);

        } catch (Exception e) {
            log.error("实时回测启动失败: {}", e.getMessage(), e);
            return com.okx.trading.model.common.ApiResponse.error(500, "实时回测启动失败: " + e.getMessage());
        }
    }


    /**
     * 获取实时回测订单记录
     */
    @GetMapping("/real-time/orders")
    @Operation(summary = "获取实时回测订单记录", description = "查询指定策略的实时交易订单记录")
    public com.okx.trading.model.common.ApiResponse<List<RealTimeOrderEntity>> getRealTimeOrders(
            @Parameter(name = "策略代码", required = false) @RequestParam(required = false) String id,
            @Parameter(name = "交易对", required = false) @RequestParam(required = false) String symbol,
            @Parameter(name = "开始时间", required = false) @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(name = "结束时间", required = false) @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {

        try {
            List<RealTimeOrderEntity> orders = realTimeOrderService.getOrdersByStrategyId(Long.parseLong(id));

            return com.okx.trading.model.common.ApiResponse.success(orders);

        } catch (Exception e) {
            log.error("获取实时订单记录失败: {}", e.getMessage(), e);
            return com.okx.trading.model.common.ApiResponse.error(500, "获取实时订单记录失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有实时策略
     */
    @GetMapping("/list")
    @Operation(summary = "获取所有实时策略", description = "获取系统中所有的实时策略列表，包括已激活和未激活的策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getAllRealTimeStrategies() {
        try {
            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getAllRealTimeStrategies();
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("获取所有实时策略失败", e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有有效的实时策略
     */
    @GetMapping("/active")
    @Operation(summary = "获取所有有效的实时策略", description = "获取系统中所有已激活状态的实时策略列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getActiveRealTimeStrategies() {
        try {
            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getActiveRealTimeStrategies();
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("获取有效实时策略失败", e);
            return com.okx.trading.util.ApiResponse.error(503, "获取有效实时策略列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取正在运行的实时策略
     */
    @GetMapping("/running")
    @Operation(summary = "获取正在运行的实时策略", description = "获取系统中所有状态为RUNNING的实时策略列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getRunningRealTimeStrategies() {
        try {
            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getRunningRealTimeStrategies();
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("获取运行中实时策略失败", e);
            return com.okx.trading.util.ApiResponse.error(503, "获取运行中实时策略列表失败: " + e.getMessage());
        }
    }

    /**
     * 根据策略代码获取实时策略
     */
    @GetMapping("/code/{strategyCode}")
    @Operation(summary = "根据策略代码获取实时策略", description = "通过唯一的策略代码查询特定的实时策略详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<RealTimeStrategyEntity> getRealTimeStrategyByCode(
            @Parameter(name = "策略代码", required = true, example = "STRATEGY_001") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            Optional<RealTimeStrategyEntity> strategy = realTimeStrategyService.getRealTimeStrategyByCode(strategyCode);
            if (strategy.isPresent()) {
                return com.okx.trading.util.ApiResponse.success(strategy.get());
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "策略不存在: " + strategyCode);
            }
        } catch (Exception e) {
            log.error("根据策略代码获取实时策略失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 根据ID获取实时策略
     */
    @GetMapping("/id/{id}")
    @Operation(summary = "根据ID获取实时策略", description = "通过数据库主键ID查询特定的实时策略详情")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "策略ID不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<RealTimeStrategyEntity> getRealTimeStrategyById(
            @Parameter(name = "策略ID", required = true, example = "1") @PathVariable Long id) {
        try {
            if (id == null) {
                return com.okx.trading.util.ApiResponse.error(503, "策略ID不能为空");
            }

            Optional<RealTimeStrategyEntity> strategy = realTimeStrategyService.getRealTimeStrategyById(id);
            if (strategy.isPresent()) {
                return com.okx.trading.util.ApiResponse.success(strategy.get());
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "策略不存在，ID: " + id);
            }
        } catch (Exception e) {
            log.error("根据ID获取实时策略失败: {}", id, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 根据策略信息代码获取有效的实时策略
     */
    @GetMapping("/info-code/{strategyCode}")
    @Operation(summary = "根据策略信息代码获取有效的实时策略", description = "通过策略信息代码查询所有关联的有效实时策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "策略信息代码不能为空"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getActiveRealTimeStrategiesByInfoCode(
            @Parameter(name = "策略信息代码", required = true, example = "MA_CROSS_STRATEGY") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略信息代码不能为空");
            }

            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getActiveRealTimeStrategiesByCode(strategyCode);
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("根据策略信息代码获取实时策略失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 根据交易对获取有效的实时策略
     */
    @GetMapping("/symbol/{symbol}")
    @Operation(summary = "根据交易对获取有效的实时策略", description = "通过交易对符号查询所有关联的有效实时策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "交易对符号不能为空"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getActiveRealTimeStrategiesBySymbol(
            @Parameter(name = "交易对符号", required = true, example = "BTC-USDT") @PathVariable String symbol) {
        try {
            if (StringUtils.isBlank(symbol)) {
                return com.okx.trading.util.ApiResponse.error(503, "交易对符号不能为空");
            }

            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getActiveRealTimeStrategiesBySymbol(symbol);
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("根据交易对获取实时策略失败: {}", symbol, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 根据状态获取实时策略
     */
    @GetMapping("/status/{status}")
    @Operation(summary = "根据状态获取实时策略", description = "通过运行状态查询所有匹配的实时策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "运行状态不能为空"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getRealTimeStrategiesByStatus(
            @Parameter(name = "运行状态", required = true, example = "RUNNING") @PathVariable String status) {
        try {
            if (StringUtils.isBlank(status)) {
                return com.okx.trading.util.ApiResponse.error(503, "运行状态不能为空");
            }

            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getRealTimeStrategiesByStatus(status);
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("根据状态获取实时策略失败: {}", status, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定时间范围内创建的实时策略
     */
    @GetMapping("/time-range")
    @Operation(summary = "获取指定时间范围内创建的实时策略", description = "查询在指定时间范围内创建的所有实时策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "400", description = "时间参数错误"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getRealTimeStrategiesByTimeRange(
            @Parameter(name = "开始时间", required = true, example = "2024-01-01 00:00:00") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @Parameter(name = "结束时间", required = true, example = "2024-12-31 23:59:59") @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime) {
        try {
            if (startTime == null || endTime == null) {
                return com.okx.trading.util.ApiResponse.error(503, "开始时间和结束时间不能为空");
            }

            if (startTime.isAfter(endTime)) {
                return com.okx.trading.util.ApiResponse.error(503, "开始时间不能晚于结束时间");
            }

            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getRealTimeStrategiesByTimeRange(startTime, endTime);
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("根据时间范围获取实时策略失败: {} - {}", startTime, endTime, e);
            return com.okx.trading.util.ApiResponse.error(503, "获取实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 更新实时策略
     */
    @PutMapping("/update")
    @Operation(summary = "更新实时策略", description = "更新现有实时策略的配置信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数错误"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<RealTimeStrategyEntity> updateRealTimeStrategy(
            @Parameter(name = "实时策略实体", required = true) @RequestBody RealTimeStrategyEntity realTimeStrategy) {
        try {
            if (realTimeStrategy == null) {
                return com.okx.trading.util.ApiResponse.error(503, "实时策略不能为空");
            }
            if (realTimeStrategy.getId() == null) {
                return com.okx.trading.util.ApiResponse.error(503, "策略ID不能为空");
            }

            RealTimeStrategyEntity updated = realTimeStrategyService.updateRealTimeStrategy(realTimeStrategy);
            return com.okx.trading.util.ApiResponse.success(updated);
        } catch (Exception e) {
            log.error("更新实时策略失败", e);
            return com.okx.trading.util.ApiResponse.error(503, "更新实时策略失败: " + e.getMessage());
        }
    }

    /**
     * 启动实时策略
     */
    @PostMapping("/start/{id}")
    @Operation(summary = "启动实时策略", description = "启动指定的实时策略，将状态设置为RUNNING")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "启动成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<String> startRealTimeStrategy(
            @Parameter(name = "策略id", required = true, example = "58") @PathVariable String id) {
        try {

            boolean success = realTimeStrategyService.startRealTimeStrategy(Long.parseLong(id));
            if (success) {
                return com.okx.trading.util.ApiResponse.success("启动策略成功: " + id);
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "启动策略失败: " + id);
            }
        } catch (Exception e) {
            log.error("启动实时策略失败: {}", id, e);
            return com.okx.trading.util.ApiResponse.error(503, "启动策略失败: " + e.getMessage());
        }
    }

    /**
     * 停止实时策略
     */
    @PostMapping("/stop/{strategyCode}")
    @Operation(summary = "停止实时策略", description = "停止指定的实时策略，将状态设置为STOPPED")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "停止成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<String> stopRealTimeStrategy(
            @Parameter(name = "策略代码", required = true, example = "STRATEGY_001") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            boolean success = realTimeStrategyService.stopRealTimeStrategy(strategyCode);
            if (success) {
                return com.okx.trading.util.ApiResponse.success("停止策略成功: " + strategyCode);
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "停止策略失败: " + strategyCode);
            }
        } catch (Exception e) {
            log.error("停止实时策略失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "停止策略失败: " + e.getMessage());
        }
    }

    /**
     * 激活策略
     */
    @PostMapping("/activate/{strategyCode}")
    @Operation(summary = "激活策略", description = "激活指定的实时策略，将isActive设置为true")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "激活成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<String> activateStrategy(
            @Parameter(name = "策略代码", required = true, example = "STRATEGY_001") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            boolean success = realTimeStrategyService.activateStrategy(strategyCode);
            if (success) {
                return com.okx.trading.util.ApiResponse.success("激活策略成功: " + strategyCode);
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "激活策略失败: " + strategyCode);
            }
        } catch (Exception e) {
            log.error("激活策略失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "激活策略失败: " + e.getMessage());
        }
    }

    /**
     * 停用策略
     */
    @PostMapping("/deactivate/{strategyCode}")
    @Operation(summary = "停用策略", description = "停用指定的实时策略，将isActive设置为false")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "停用成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<String> deactivateStrategy(
            @Parameter(name = "策略代码") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            boolean success = realTimeStrategyService.deactivateStrategy(strategyCode);
            if (success) {
                return com.okx.trading.util.ApiResponse.success("停用策略成功: " + strategyCode);
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "停用策略失败: " + strategyCode);
            }
        } catch (Exception e) {
            log.error("停用策略失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "停用策略失败: " + e.getMessage());
        }
    }

    /**
     * 删除实时策略
     */
    @PostMapping("/delete/{id}")
    @Operation(summary = "删除实时策略", description = "永久删除指定的实时策略记录")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "删除成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<String> deleteRealTimeStrategy(
            @Parameter(name = "策略代码") @PathVariable String id) {
        try {
            if (StringUtils.isBlank(id)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            boolean deleted = realTimeStrategyService.deleteRealTimeStrategy(id);
            if (deleted) {
                return com.okx.trading.util.ApiResponse.success("策略删除成功");
            } else {
                return com.okx.trading.util.ApiResponse.error(503, "策略删除失败，策略可能不存在或已被删除");
            }
        } catch (Exception e) {
            log.error("删除策略失败: {}", id, e);
            return com.okx.trading.util.ApiResponse.error(503, "删除策略失败: " + e.getMessage());
        }
    }

    /**
     * 复制实时策略
     */
    @PostMapping("/copy/{id}")
    @Operation(summary = "复制实时策略", description = "复制一个已有的实时策略，创建一个新的策略实例")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "复制成功"),
            @ApiResponse(responseCode = "400", description = "策略ID不能为空"),
            @ApiResponse(responseCode = "404", description = "策略不存在"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<RealTimeStrategyEntity> copyRealTimeStrategy(
            @Parameter(name = "策略ID", required = true, example = "1") @PathVariable Long id) {
        try {
            if (id == null || id <= 0) {
                return com.okx.trading.util.ApiResponse.error(400, "策略ID不能为空或无效");
            }

            RealTimeStrategyEntity copiedStrategy = realTimeStrategyService.copyRealTimeStrategy(id);
            return com.okx.trading.util.ApiResponse.success(copiedStrategy);
        } catch (IllegalArgumentException e) {
            log.error("复制策略失败，策略不存在: {}", id);
            return com.okx.trading.util.ApiResponse.error(404, "复制策略失败，策略不存在");
        } catch (Exception e) {
            log.error("复制策略失败: {}", id, e);
            return com.okx.trading.util.ApiResponse.error(500, "复制策略失败: " + e.getMessage());
        }
    }

    /**
     * 检查策略代码是否已存在
     */
    @GetMapping("/exists/{strategyCode}")
    @Operation(summary = "检查策略代码是否已存在", description = "验证指定的策略代码是否已被使用")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "检查成功"),
            @ApiResponse(responseCode = "400", description = "策略代码不能为空"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<Boolean> existsByStrategyCode(
            @Parameter(name = "策略代码") @PathVariable String strategyCode) {
        try {
            if (StringUtils.isBlank(strategyCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略代码不能为空");
            }

            boolean exists = realTimeStrategyService.existsByStrategyCode(strategyCode);
            return com.okx.trading.util.ApiResponse.success(exists);
        } catch (Exception e) {
            log.error("检查策略代码是否存在失败: {}", strategyCode, e);
            return com.okx.trading.util.ApiResponse.error(503, "检查策略代码失败: " + e.getMessage());
        }
    }

    /**
     * 检查是否存在运行中的策略
     */
    @GetMapping("/has-running")
    @Operation(summary = "检查是否存在运行中的策略", description = "检查指定策略信息代码和交易对是否有正在运行的策略")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "检查成功"),
            @ApiResponse(responseCode = "400", description = "参数不能为空"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<Boolean> hasRunningStrategy(
            @Parameter(name = "策略信息代码", required = true, example = "MA_CROSS_STRATEGY") @RequestParam String strategyInfoCode,
            @Parameter(name = "交易对符号", required = true, example = "BTC-USDT") @RequestParam String symbol) {
        try {
            if (StringUtils.isBlank(strategyInfoCode)) {
                return com.okx.trading.util.ApiResponse.error(503, "策略信息代码不能为空");
            }
            if (StringUtils.isBlank(symbol)) {
                return com.okx.trading.util.ApiResponse.error(503, "交易对符号不能为空");
            }

            boolean hasRunning = realTimeStrategyService.hasRunningStrategy(strategyInfoCode, symbol);
            return com.okx.trading.util.ApiResponse.success(hasRunning);
        } catch (Exception e) {
            log.error("检查运行中策略失败: {} - {}", strategyInfoCode, symbol, e);
            return com.okx.trading.util.ApiResponse.error(503, "检查运行中策略失败: " + e.getMessage());
        }
    }

    /**
     * 获取需要自动启动的策略
     */
    @GetMapping("/auto-start")
    @Operation(summary = "获取需要自动启动的策略", description = "获取所有标记为自动启动且状态为RUNNING的策略列表")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<List<RealTimeStrategyEntity>> getStrategiesToAutoStart() {
        try {
            List<RealTimeStrategyEntity> strategies = realTimeStrategyService.getStrategiesToAutoStart();
            return com.okx.trading.util.ApiResponse.success(strategies);
        } catch (Exception e) {
            log.error("获取自动启动策略失败", e);
            return com.okx.trading.util.ApiResponse.error(503, "获取自动启动策略失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前持有仓位的策略预估收益
     */
    @GetMapping("/holding-positions")
    @Operation(summary = "获取持仓中的策略预估收益", description = "获取最后交易状态为买入(BUY)的所有正在运行策略的预估收益信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "500", description = "服务器内部错误")
    })
    public com.okx.trading.util.ApiResponse<Map<String, Object>> getHoldingPositionsProfits() {
        try {
            // 获取所有正在运行的策略
            Map<Long, RealTimeStrategyEntity> allRunningStrategies = realTimeStrategyManager.getAllRunningStrategies();
            List<Map<String, Object>> strategiesList = new ArrayList<>();

            // 统计指标
            BigDecimal totalEstimatedProfit = BigDecimal.ZERO;
            BigDecimal totalInvestmentAmount = BigDecimal.ZERO;
            BigDecimal totalRealizedProfit = BigDecimal.ZERO;
            int holdingStrategiesCount = 0;
            int runningStrategiesCount = 0;

            // 筛选出最后交易类型为买入(BUY)的策略
            for (RealTimeStrategyEntity strategy : allRunningStrategies.values()) {
                // 统计运行中策略总数
                runningStrategiesCount++;
                
                // 累计已实现总收益
                if (strategy.getTotalProfit() != null) {
                    totalRealizedProfit = totalRealizedProfit.add(BigDecimal.valueOf(strategy.getTotalProfit()));
                }
                
                // 累计总投资金额
                if (strategy.getTradeAmount() != null) {
                    totalInvestmentAmount = totalInvestmentAmount.add(BigDecimal.valueOf(strategy.getTradeAmount()));
                }
                
                // 处理持仓中策略
                if ("BUY".equals(strategy.getLastTradeType())) {
                    holdingStrategiesCount++;
                    Map<String, Object> strategyProfit = new HashMap<>();

                    try {
                        // 获取基本信息
                        strategyProfit.put("strategyId", strategy.getId());
                        strategyProfit.put("strategyCode", strategy.getStrategyCode());
                        strategyProfit.put("strategyName", strategy.getStrategyName());
                        strategyProfit.put("symbol", strategy.getSymbol());
                        strategyProfit.put("interval", strategy.getInterval());

                        // 入场信息
                        strategyProfit.put("entryPrice", strategy.getLastTradePrice());
                        strategyProfit.put("entryAmount", new BigDecimal(strategy.getLastTradeAmount()).setScale(4, RoundingMode.HALF_UP));
                        strategyProfit.put("entryTime", strategy.getLastTradeTime().format(dateFormat));

                        // 获取最新价格
                        Ticker latestTicker = okxApiService.getTicker(strategy.getSymbol());
                        BigDecimal currentPrice = latestTicker != null ? latestTicker.getLastPrice() : null;

                        if (currentPrice != null && strategy.getLastTradePrice() != null) {
                            // 计算当前持仓价值
                            double entryPrice = strategy.getLastTradePrice();
                            double quantity = strategy.getLastTradeQuantity();
                            double currentValue = currentPrice.doubleValue() * quantity;
                            double entryValue = entryPrice * quantity;

                            // 计算预估收益和收益率
                            double estimatedProfit = currentValue - entryValue;
                            double profitPercentage = (estimatedProfit / entryValue) * 100;

                            // 添加预估收益信息 - 格式化为4位小数
                            strategyProfit.put("currentPrice", currentPrice);
                            strategyProfit.put("quantity", new BigDecimal(quantity).setScale(8, RoundingMode.HALF_UP));
                            strategyProfit.put("currentValue", new BigDecimal(currentValue).setScale(4, RoundingMode.HALF_UP));
                            strategyProfit.put("estimatedProfit", new BigDecimal(estimatedProfit).setScale(4, RoundingMode.HALF_UP));
                            strategyProfit.put("profitPercentage", new BigDecimal(profitPercentage).setScale(2, RoundingMode.HALF_UP) + "%");

                            // 累加预估收益到总预估收益
                            totalEstimatedProfit = totalEstimatedProfit.add(new BigDecimal(estimatedProfit));

                            // 计算持仓时长
                            Duration holdingDuration = Duration.between(strategy.getLastTradeTime(), LocalDateTime.now());
                            long days = holdingDuration.toDays();
                            long hours = holdingDuration.minusDays(days).toHours();
                            long minutes = holdingDuration.minusDays(days).minusHours(hours).toMinutes();

                            String durationStr = "";
                            if (days > 0) {
                                durationStr += days + "天";
                            }
                            if (hours > 0 || days > 0) {
                                durationStr += hours + "小时";
                            }
                            durationStr += minutes + "分钟";

                            strategyProfit.put("holdingDuration", durationStr);
                        } else {
                            // 如果无法获取最新价格
                            strategyProfit.put("currentPrice", "未知");
                            strategyProfit.put("estimatedProfit", "未知");
                            strategyProfit.put("profitPercentage", "未知");
                        }

                        strategiesList.add(strategyProfit);
                    } catch (Exception e) {
                        log.error("计算策略{}的预估收益时出错: {}", strategy.getStrategyCode(), e.getMessage());
                        // 继续处理下一个策略
                    }
                }
            }

            // 按预估收益率降序排序
            strategiesList.sort((a, b) -> {
                Object aProfit = a.get("profitPercentage");
                Object bProfit = b.get("profitPercentage");

                // 处理可能的字符串情况
                if (aProfit instanceof String && bProfit instanceof String) {
                    if (aProfit.equals("未知") && bProfit.equals("未知")) {
                        return 0;
                    } else if (aProfit.equals("未知")) {
                        return 1;
                    } else if (bProfit.equals("未知")) {
                        return -1;
                    }
                }

                // 如果都是数值，则比较
                if (aProfit instanceof String && ((String) aProfit).endsWith("%")) {
                    aProfit = new BigDecimal(((String) aProfit).replace("%", ""));
                }
                if (bProfit instanceof String && ((String) bProfit).endsWith("%")) {
                    bProfit = new BigDecimal(((String) bProfit).replace("%", ""));
                }

                if (aProfit instanceof BigDecimal && bProfit instanceof BigDecimal) {
                    return ((BigDecimal) bProfit).compareTo((BigDecimal) aProfit);
                }

                return 0;
            });

            // 创建包含策略列表和统计信息的返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("strategies", strategiesList);
            
            // 添加统计信息
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("totalEstimatedProfit", totalEstimatedProfit.setScale(4, RoundingMode.HALF_UP));
            statistics.put("totalRealizedProfit", totalRealizedProfit.setScale(4, RoundingMode.HALF_UP));
            statistics.put("totalInvestmentAmount", totalInvestmentAmount.setScale(4, RoundingMode.HALF_UP));
            statistics.put("holdingStrategiesCount", holdingStrategiesCount);
            statistics.put("runningStrategiesCount", runningStrategiesCount);
            
            // 计算总收益(已实现收益 + 未实现收益)
            BigDecimal totalProfit = totalRealizedProfit.add(totalEstimatedProfit);
            statistics.put("totalProfit", totalProfit.setScale(4, RoundingMode.HALF_UP));
            
            // 计算总收益率
            if (totalInvestmentAmount.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal totalProfitRate = totalProfit.multiply(new BigDecimal("100")).divide(totalInvestmentAmount, 2, RoundingMode.HALF_UP);
                statistics.put("totalProfitRate", totalProfitRate + "%");
            } else {
                statistics.put("totalProfitRate", "0.00%");
            }
            
            result.put("statistics", statistics);

            return com.okx.trading.util.ApiResponse.success(result);
        } catch (Exception e) {
            log.error("获取持仓策略预估收益失败", e);
            return com.okx.trading.util.ApiResponse.error(500, "获取持仓策略预估收益失败: " + e.getMessage());
        }
    }
}
