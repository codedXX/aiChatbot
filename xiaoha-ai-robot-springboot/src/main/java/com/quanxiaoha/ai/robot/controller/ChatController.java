package com.quanxiaoha.ai.robot.controller;

import com.google.common.collect.Lists;
import com.quanxiaoha.ai.robot.advisor.CustomChatMemoryAdvisor;
import com.quanxiaoha.ai.robot.advisor.CustomStreamLoggerAndMessage2DBAdvisor;
import com.quanxiaoha.ai.robot.advisor.NetworkSearchAdvisor;
import com.quanxiaoha.ai.robot.aspect.ApiOperationLog;
import com.quanxiaoha.ai.robot.domain.mapper.ChatMessageMapper;
import com.quanxiaoha.ai.robot.model.vo.chat.*;
import com.quanxiaoha.ai.robot.service.ChatService;
import com.quanxiaoha.ai.robot.service.SearXNGService;
import com.quanxiaoha.ai.robot.service.SearchResultContentFetcherService;
import com.quanxiaoha.ai.robot.utils.PageResponse;
import com.quanxiaoha.ai.robot.utils.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;


/**
 * @Author: 犬小哈
 * @Date: 2025/5/22 12:25
 * @Version: v1.0.0
 * @Description: 对话
 **/
@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatService chatService;
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;
    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Resource
    private ChatMessageMapper chatMessageMapper;
    @Resource
    private TransactionTemplate transactionTemplate;
    @Resource
    private SearXNGService searXNGService;
    @Resource
    private SearchResultContentFetcherService searchResultContentFetcherService;

    @PostMapping("/new")
    @ApiOperationLog(description = "新建对话")
    public Response<?> newChat(@RequestBody @Validated NewChatReqVO newChatReqVO) {
        return chatService.newChat(newChatReqVO);
    }

    /**
     * 流式对话
     * @return
     */
    @PostMapping(value = "/completion", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiOperationLog(description = "流式对话")
    public Flux<AIResponse> chat(@RequestBody @Validated AiChatReqVO aiChatReqVO) {
        // 用户消息
        String userMessage = aiChatReqVO.getMessage();
        // 模型名称
        String modelName = aiChatReqVO.getModelName();
        // 温度值
        Double temperature = aiChatReqVO.getTemperature();
        // 是否开启联网搜索
        boolean networkSearch = aiChatReqVO.getNetworkSearch();

        // 构建 ChatModel（每次请求动态创建，以支持前端传入不同的模型和参数）
        ChatModel chatModel = OpenAiChatModel.builder()  // 创建 OpenAiChatModel 的 Builder 构建器
                .openAiApi(OpenAiApi.builder()           // 设置底层 HTTP 通信客户端
                        .baseUrl(baseUrl)                // API 请求地址，如 https://api.openai.com
                        .apiKey(apiKey)                  // 鉴权密钥
                        .build())                        // 构建 OpenAiApi 对象
                .build();                                // 构建最终的 ChatModel 对象

        // 动态设置调用的模型名称、温度值（由前端请求参数决定）
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = // 声明请求规格对象（含 Advisor、prompt 等配置）
                ChatClient.create(chatModel)             // 用上面的 ChatModel 创建一个一次性 ChatClient
                        .prompt()                        // 开始构建本次对话的 Prompt
                        .options(OpenAiChatOptions.builder()  // 设置本次调用的运行时选项
                                .model(modelName)        // 指定模型名称，如 gpt-4o、deepseek-chat
                                .temperature(temperature)// 温度值：越高越有创意(1.0)，越低越保守(0.0)
                                .build())                // 构建 OpenAiChatOptions 对象
                        .user(userMessage);              // 设置用户输入的消息内容（即用户提示词）

        // Advisor 集合（类似过滤器链，按 order 值从小到大依次执行）
        List<Advisor> advisors = Lists.newArrayList();

        // 是否开启了联网搜索
        if (networkSearch) {
            // 开启联网搜索时：添加联网搜索 Advisor，会在调用 AI 前先搜索相关内容作为上下文
            advisors.add(new NetworkSearchAdvisor(searXNGService, searchResultContentFetcherService));
        } else {
            // 未开启联网搜索时：添加对话记忆 Advisor，从数据库拉取最新 50 条历史消息拼入上下文
            advisors.add(new CustomChatMemoryAdvisor(chatMessageMapper, aiChatReqVO, 50));
        }

        // 添加日志+存库 Advisor：流式输出过程中打印日志，流式完成后将用户消息和 AI 回答存入数据库
        advisors.add(new CustomStreamLoggerAndMessage2DBAdvisor(chatMessageMapper, aiChatReqVO, transactionTemplate));

        // 将 Advisor 列表挂载到本次请求，调用 stream() 时会按顺序触发
        chatClientRequestSpec.advisors(advisors);

        // 流式输出
        return chatClientRequestSpec
                .stream()
                .content()
                .mapNotNull(text -> AIResponse.builder().v(text).build()); // 构建返参 AIResponse

    }

    @PostMapping("/list")
    @ApiOperationLog(description = "查询历史对话")
    public PageResponse<FindChatHistoryPageListRspVO> findChatHistoryPageList(@RequestBody @Validated FindChatHistoryPageListReqVO findChatHistoryPageListReqVO) {
        return chatService.findChatHistoryPageList(findChatHistoryPageListReqVO);
    }

    @PostMapping("/message/list")
    @ApiOperationLog(description = "查询对话历史消息")
    public PageResponse<FindChatHistoryMessagePageListRspVO> findChatMessagePageList(@RequestBody @Validated FindChatHistoryMessagePageListReqVO findChatHistoryMessagePageListReqVO) {
        return chatService.findChatHistoryMessagePageList(findChatHistoryMessagePageListReqVO);
    }

    @PostMapping("/summary/rename")
    @ApiOperationLog(description = "重命名对话摘要")
    public Response<?> renameChatSummary(@RequestBody @Validated RenameChatReqVO renameChatReqVO) {
        return chatService.renameChatSummary(renameChatReqVO);
    }

    @PostMapping("/delete")
    @ApiOperationLog(description = "删除对话")
    public Response<?> deleteChat(@RequestBody @Validated DeleteChatReqVO deleteChatReqVO) {
        return chatService.deleteChat(deleteChatReqVO);
    }


}
