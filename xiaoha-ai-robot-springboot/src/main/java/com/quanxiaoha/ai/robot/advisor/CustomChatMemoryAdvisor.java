package com.quanxiaoha.ai.robot.advisor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Lists;
import com.quanxiaoha.ai.robot.domain.dos.ChatMessageDO;
import com.quanxiaoha.ai.robot.domain.mapper.ChatMessageMapper;
import com.quanxiaoha.ai.robot.model.vo.chat.AiChatReqVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @Author: 犬小哈
 * @Date: 2025/5/26 16:36
 * @Version: v1.0.0
 * @Description: 自定义对话记忆 Advisor
 **/
@Slf4j
public class CustomChatMemoryAdvisor implements StreamAdvisor {

    private final ChatMessageMapper chatMessageMapper; // 用于查询历史消息的 Mapper
    private final AiChatReqVO aiChatReqVO;            // 本次请求参数（含 chatId、用户消息等）
    private final int limit;                          // 最多携带的历史消息条数

    public CustomChatMemoryAdvisor(ChatMessageMapper chatMessageMapper, AiChatReqVO aiChatReqVO, int limit) {
        this.chatMessageMapper = chatMessageMapper;
        this.aiChatReqVO = aiChatReqVO;
        this.limit = limit;
    }

    @Override
    public int getOrder() {
        return 2; // order 值越小越先执行，此 Advisor 在存库 Advisor（order=99）之前执行
    }

    @Override
    public String getName() {
        return this.getClass().getSimpleName(); // 返回类名作为 Advisor 标识
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        log.info("## 自定义聊天记忆 Advisor...");

        // 对话 UUID，用于从数据库查询该对话下的历史消息
        String chatUuid = aiChatReqVO.getChatId();

        // 从数据库查询该对话最新的 limit 条历史消息（降序取最新，再升序还原时序）
        List<ChatMessageDO> messages = chatMessageMapper.selectList(Wrappers.<ChatMessageDO>lambdaQuery()
                .eq(ChatMessageDO::getChatUuid, chatUuid)          // 过滤指定对话
                .orderByDesc(ChatMessageDO::getCreateTime)          // 降序：取最新的 limit 条
                .last(String.format("LIMIT %d", limit)));           // SQL 末尾追加 LIMIT N

        // 将降序结果重新按时间升序排列，保证消息顺序正确（旧 → 新）
        List<ChatMessageDO> sortedMessages = messages.stream()
                 .sorted(Comparator.comparing(ChatMessageDO::getCreateTime))
                 .toList();

        // 用于拼装发送给 AI 的完整消息列表（历史记忆 + 本次用户消息）
        List<Message> messageList = Lists.newArrayList();

        // 将数据库记录按角色转换为 Spring AI 的 Message 类型
        for (ChatMessageDO chatMessageDO : sortedMessages) {
            String type = chatMessageDO.getRole(); // 消息角色：user 或 assistant
            if (Objects.equals(type, MessageType.USER.getValue())) {
                // 用户消息 → UserMessage
                messageList.add(new UserMessage(chatMessageDO.getContent()));
            } else if (Objects.equals(type, MessageType.ASSISTANT.getValue())) {
                // AI 回答 → AssistantMessage
                messageList.add(new AssistantMessage(chatMessageDO.getContent()));
            }
        }

        // 将本次用户消息追加到历史记忆末尾（来自原始请求的 prompt 指令）
        messageList.addAll(chatClientRequest.prompt().getInstructions());

        // 用拼装好的完整消息列表重建请求对象，传递给下一个 Advisor 或 AI 模型
        ChatClientRequest processedChatClientRequest = chatClientRequest
                .mutate()
                .prompt(chatClientRequest.prompt().mutate().messages(messageList).build())
                .build();

        // 继续执行 Advisor 链中的下一个节点
        return streamAdvisorChain.nextStream(processedChatClientRequest);
    }
}
