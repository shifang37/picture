package com.tzy.picturebackend.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用 Mockito 模拟 StringRedisTemplate 的最小内存实现。
 * <p>
 * 只覆盖单元测试用到的 get / getAndDelete / set(带 TTL) / increment / delete，
 * 避免为了跑单测启动真实 Redis；真实 Redis 的行为由后续的集成测试覆盖。
 */
public final class InMemoryRedis {

    private InMemoryRedis() {
    }

    public static StringRedisTemplate createTemplate(Map<String, String> store) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);

        when(ops.get(anyString())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));

        // 验证码校验依赖 GETDEL：读取并删除必须一次完成
        when(ops.getAndDelete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return store.remove(key);
        });

        when(ops.increment(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            long next = Long.parseLong(store.getOrDefault(key, "0")) + 1;
            store.put(key, String.valueOf(next));
            return next;
        });

        doAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));

        when(template.delete(anyString())).thenAnswer(invocation -> {
            store.remove(invocation.getArgument(0));
            return Boolean.TRUE;
        });

        return template;
    }
}
