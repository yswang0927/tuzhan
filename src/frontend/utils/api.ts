import { useState, useEffect, useCallback, useRef } from 'react';

const BASE_API_URL = (window as any).BASE_URL || 'http://127.0.0.1:9090';

/**
 * HTTP / 业务请求错误。
 * - statusCode: HTTP 状态码；业务错误（HTTP 200 但 code !== 0）时为 undefined。
 * - code: 后端返回的业务错误码（如有）。
 */
export class HttpError extends Error {
    readonly statusCode?: number;
    readonly code?: number;

    constructor(message: string, options?: { statusCode?: number; code?: number }) {
        super(message);
        this.name = 'HttpError';
        this.statusCode = options?.statusCode;
        this.code = options?.code;
        // 修正继承内置类时的原型链，保证 instanceof 生效
        Object.setPrototypeOf(this, HttpError.prototype);
    }
}

/**
 * get 请求封装，直接返回后端data字段
 * @param {string} url
 * @param {Object} [params] query参数对象
 * @returns {Promise<any>} 返回后端response.data
 */
export async function getJson<T = any>(
    url: string,
    params?: Record<string, any>,
    signal?: AbortSignal
): Promise<T> {
    let queryUrl = url;
    if (params && typeof params === 'object') {
        const sp = new URLSearchParams(params);
        queryUrl += (url.includes('?') ? '&' : '?') + sp.toString();
    }

    const res = await fetch(`${BASE_API_URL}${queryUrl}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        signal: signal
    });

    // http层面错误，如404 500
    if (!res.ok) {
        if(res.status === 401){
            // 跳登录页
            location.href = '/login';
            throw new HttpError('HTTP 401 Unauthorized', { statusCode: 401 });
        }
        throw new HttpError(`HTTP ${res.status}`, { statusCode: res.status });
    }

    const json = await res.json();
    // 业务code判断：code !==0 业务失败
    if (json.code !== 0) {
        // 把后端message抛出，上层catch捕获
        throw new HttpError(json.message || '业务请求失败', { code: json.code });
    }

    return json.data;
}

export async function postJson<T = any>(
    url: string,
    body: Record<string, any>,
    signal?: AbortSignal
): Promise<T> {
    const res = await fetch(`${BASE_API_URL}${url}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        credentials: 'include',
        signal: signal
    });

    if (!res.ok) {
        if(res.status === 401){
            // 跳登录页
            location.href = '/login';
            throw new HttpError('HTTP 401 Unauthorized', { statusCode: 401 });
        }
        throw new HttpError(`HTTP ${res.status}`, { statusCode: res.status });
    }

    const json = await res.json();
    if (json.code !== 0) {
        throw new HttpError(json.message || '业务请求失败', { code: json.code });
    }

    return json.data;
}

/**
 * ============ useFetch() ============
 * 用法：
 * const {data, loading, error} = useFetch('/api/xxx');
 *
 * 手动触发：
 * const {data, loading, error, run} = useFetch('/api/xxx', {immediate:false});
 * <button onClick={run} disabled={loading}>
 *  {loading ? 'Loading' : 'Edit'}
 * </button>
 *
 * 轮寻：
 * const { data, run, abort } = useFetch('/api/xxx', {
 *  pollingInterval: 3000
 * });
 *
 * 防抖：
 * const { data, run, abort } = useFetch('/api/xxx', {
 *  debounceInterval: 300
 * });
 *
 * 节流：
 * const { data, run, abort } = useFetch('/api/xxx', {
 *  throttleInterval: 300
 * });
 *
 * 错误重试：
 * const { data, run, abort } = useFetch('/api/xxx', {
 *  retryCount: 3,
 *  retryInterval: 1000,
 *  maxRetryInterval: 10000
 * });
 *
 * SSE监听：
 * const {loading} = useFetch('/api/xxx', {
 *   onSSE: (event: SSEEvent) => {}
 * });
 */

export interface FetchSSEOptions {
    method?: 'GET' | 'POST';
    params?: Record<string, any>;
    body?: Record<string, any>;
    signal?: AbortSignal;
    onEvent: (event: SSEEvent) => void;
}

/**
 * SSE（text/event-stream）单条事件
 */
export interface SSEEvent {
    event: string;      // 事件类型，缺省为 'message'
    data: string;       // data 字段（多行 data 以 \n 拼接）
    id?: string;        // 事件 id
    retry?: number;     // 服务端建议的重连间隔(ms)
}

// 解析单个 SSE 帧（帧内以换行分隔的多个字段）
function parseSSEFrame(frame: string): SSEEvent | null {
    if (!frame) return null;

    let event = 'message';
    let id: string | undefined;
    let retry: number | undefined;
    const dataLines: string[] = [];

    for (const line of frame.split('\n')) {
        // 空行或以 ':' 开头的注释行，跳过
        if (!line || line.startsWith(':')) continue;

        const colon = line.indexOf(':');
        const field = colon === -1 ? line : line.slice(0, colon);
        let value = colon === -1 ? '' : line.slice(colon + 1);
        // 规范规定去掉 value 的一个前导空格
        if (value.startsWith(' ')) {
            value = value.slice(1);
        }

        switch (field) {
            case 'event':
                event = value;
                break;
            case 'data':
                dataLines.push(value);
                break;
            case 'id':
                id = value;
                break;
            case 'retry': {
                const n = parseInt(value, 10);
                if (!Number.isNaN(n)) retry = n;
                break;
            }
        }
    }

    // 纯注释/空帧，无有效字段则忽略
    if (dataLines.length === 0 && id === undefined && retry === undefined && event === 'message') {
        return null;
    }

    return { event, data: dataLines.join('\n'), id, retry };
}

/**
 * 发起 SSE 流式请求，逐条回调 onEvent，直到流结束（Promise resolve）或出错（reject）。
 */
export async function fetchSSE(
    url: string,
    options: FetchSSEOptions
): Promise<void> {
    const { method = 'GET', params, body, signal, onEvent } = options;

    let queryUrl = url;
    if (method === 'GET' && params && typeof params === 'object') {
        const sp = new URLSearchParams(params);
        queryUrl += (url.includes('?') ? '&' : '?') + sp.toString();
    }

    const res = await fetch(`${BASE_API_URL}${queryUrl}`, {
        method,
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'text/event-stream',
        },
        body: method === 'POST' ? JSON.stringify(body ?? params ?? {}) : undefined,
        credentials: 'include',
        signal: signal,
    });

    if (!res.ok) {
        if (res.status === 401) {
            location.href = '/login';
            throw new Error('HTTP 401');
        }
        throw new HttpError(`HTTP ${res.status}`, { statusCode: res.status });
    }

    if (!res.body) {
        throw new Error('SSE 响应无 body（当前环境不支持流式读取）');
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) {
                break;
            }

            // 统一换行为 \n，再按空行（\n\n）切分帧
            buffer += decoder.decode(value, { stream: true }).replace(/\r\n?/g, '\n');

            let sep: number;
            while ((sep = buffer.indexOf('\n\n')) !== -1) {
                const frame = buffer.slice(0, sep);
                buffer = buffer.slice(sep + 2);
                if (signal?.aborted) {
                    return;
                }
                const evt = parseSSEFrame(frame);
                if (evt) {
                    onEvent(evt);
                }
            }
        }

        // flush 尾部残余（末尾无空行的最后一帧）
        const tail = (buffer + decoder.decode()).trim();
        if (tail && !signal?.aborted) {
            const evt = parseSSEFrame(tail);
            if (evt) {
                onEvent(evt);
            }
        }
    } finally {
        // 主动释放底层连接
        reader.cancel().catch(() => { /* 忽略取消错误 */ });
    }
}

// 安全调用生命周期回调，回调内部抛错不影响请求主流程
function safeCall(fn: ((...args: any[]) => void) | undefined, ...args: any[]) {
    if (typeof fn !== 'function') {
        return;
    }
    try {
        fn(...args);
    } catch (e) {
        console.error('[useFetch] lifecycle callback error:', e);
    }
}

export interface UseFetchOptions<T = any> {
    method?: 'GET' | 'POST';
    params?: Record<string, any>;
    immediate?: boolean;
    pollingInterval?: number;
    debounceInterval?: number;
    throttleInterval?: number;
    refreshOnWindowFocus?: boolean;

    // 指数退避重试配置
    retryCount?: number;
    retryInterval?: number;
    maxRetryInterval?: number;
    retryBackoff?: boolean;
    retryJitter?: boolean;

    // 生命周期回调
    onBefore?: (params: Record<string, any>) => void;
    onSuccess?: (data: T, params: Record<string, any>) => void;
    onError?: (error: Error, params: Record<string, any>) => void;
    onFinally?: (params: Record<string, any>, data?: T, error?: Error) => void;

    // SSE（text/event-stream）推送回调。设置后请求走流式读取，
    // 每收到一条事件触发一次 onSSE；流正常结束时触发 onSuccess，出错触发 onError。
    onSSE?: (event: SSEEvent) => void;
}

export interface UseFetchReturn<T> {
    data: T | null;
    loading: boolean;
    error: Error | null;
    refetch: (newParams?: Record<string, any>) => Promise<T | undefined>;
    run: (newParams?: Record<string, any>) => Promise<T | undefined>;
    abort: () => void;
}

export function useFetch<T = any>(
    url: string,
    options: UseFetchOptions<T> = {}
): UseFetchReturn<T> {
    const {
        params,
        immediate = true,
        refreshOnWindowFocus = false,
    } = options;

    const [data, setData] = useState<T | null>(null);
    const [loading, setLoading] = useState<boolean>(immediate);
    const [error, setError] = useState<Error | null>(null);

    const optionsRef = useRef(options);
    optionsRef.current = options;

    const paramsRef = useRef(params);
    paramsRef.current = params;

    const abortControllerRef = useRef<AbortController | null>(null);
    const pollingTimerRef = useRef<number | null>(null);
    const debounceTimerRef = useRef<number | null>(null);
    const throttleTimerRef = useRef<number | null>(null);
    const retryTimerRef = useRef<number | null>(null);
    const isThrottledRef = useRef<boolean>(false);
    // 组件挂载标记：卸载后阻止一切 setState 与回调，避免内存泄漏与无效状态更新
    const isMountedRef = useRef<boolean>(true);

    const clearTimers = useCallback(() => {
        if (pollingTimerRef.current) clearTimeout(pollingTimerRef.current);
        if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
        if (throttleTimerRef.current) clearTimeout(throttleTimerRef.current);
        if (retryTimerRef.current) clearTimeout(retryTimerRef.current);
    }, []);

    // 核心请求（含指数退避重试）
    const executeCore = useCallback(
        async (
            overrideParams?: Record<string, any>,
            currentRetry = 0
        ): Promise<T | undefined> => {
            // 组件已卸载则不再发起请求
            if (!isMountedRef.current) {
                return;
            }

            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }

            // 发起新请求前清理挂起的轮询/重试定时器，避免定时器泄漏与请求链翻倍
            if (pollingTimerRef.current) {
                clearTimeout(pollingTimerRef.current);
                pollingTimerRef.current = null;
            }
            if (retryTimerRef.current) {
                clearTimeout(retryTimerRef.current);
                retryTimerRef.current = null;
            }

            const controller = new AbortController();
            abortControllerRef.current = controller;

            setLoading(true);
            if (currentRetry === 0) {
                setError(null);
            }

            const requestParams = overrideParams ?? paramsRef.current ?? {};
            const {
                method = 'GET',
                pollingInterval,
                retryCount = 0,
                retryInterval = 1000,
                maxRetryInterval = 30000,
                retryBackoff = true,
                retryJitter = false,
                onBefore,
                onSuccess,
                onError,
                onFinally,
                onSSE,
            } = optionsRef.current;

            // 请求前回调（仅在首次尝试触发，重试不重复触发）
            if (currentRetry === 0) {
                safeCall(onBefore, requestParams);
            }

            try {
                // SSE 流式分支：设置了 onSSE 则走流式读取，逐条推送
                if (typeof onSSE === 'function') {
                    const sseOptions: FetchSSEOptions = {
                        method: (method === 'POST' ? 'POST' : 'GET'),
                        params: method === 'GET' ? requestParams : undefined,
                        body: method === 'POST' ? requestParams : undefined,
                        signal: controller.signal,
                        onEvent: (evt: SSEEvent) => {
                            if (controller.signal.aborted || !isMountedRef.current) {
                                return;
                            }
                            safeCall(onSSE, evt);
                        },
                    };

                    await fetchSSE(url, sseOptions);

                    if (controller.signal.aborted || !isMountedRef.current) {
                        return;
                    }

                    // 流正常结束：视为一次成功（无聚合 data）
                    setError(null);
                    setLoading(false);
                    safeCall(onSuccess, undefined as unknown as T, requestParams);
                    safeCall(onFinally, requestParams, undefined, undefined);

                    // 轮询：流结束后按间隔重新建立连接
                    if (pollingInterval && pollingInterval > 0) {
                        pollingTimerRef.current = setTimeout(() => {
                            executeCore(overrideParams, 0);
                        }, pollingInterval);
                    }

                    return;
                }

                // 普通常规的http请求
                const result = (method === 'GET')
                        ? await getJson<T>(url, requestParams, controller.signal)
                        : await postJson<T>(url, requestParams, controller.signal);

                if (controller.signal.aborted || !isMountedRef.current) {
                    return;
                }

                setData(result);
                setError(null);
                setLoading(false);

                // 成功回调
                safeCall(onSuccess, result, requestParams);
                safeCall(onFinally, requestParams, result, undefined);

                // 如果启用了轮寻
                if (pollingInterval && pollingInterval > 0) {
                    pollingTimerRef.current = setTimeout(() => {
                        executeCore(overrideParams, 0);
                    }, pollingInterval);
                }

                return result;

            } catch (err: any) {
                if ((typeof err === 'object' && err.name === 'AbortError')
                    || controller.signal.aborted
                    || !isMountedRef.current) {
                    return;
                }

                const errorObj = err instanceof Error ? err : new Error(String(err));

                // 4xx 客户端错误（如 400/403/404）重试也不会成功，直接失败；
                // 仅对网络错误和 5xx 服务端错误进行重试。
                const statusCode = errorObj instanceof HttpError ? errorObj.statusCode : undefined;
                const isBusinessError = errorObj instanceof HttpError && errorObj.code !== undefined;
                const isRetriable = retryCount > 0 && !isBusinessError && (statusCode === undefined || statusCode >= 500);

                // 触发重试逻辑
                if (isRetriable && currentRetry < retryCount) {
                    // 1. 指数退避: base * 2^(currentRetry)
                    let delay = retryBackoff
                        ? retryInterval * Math.pow(2, currentRetry)
                        : retryInterval;

                    // 2. 封顶最大延迟
                    delay = Math.min(delay, maxRetryInterval);

                    // 3. 可选：全抖动 (Full Jitter)
                    if (retryJitter) {
                        delay = Math.floor(Math.random() * delay);
                    }

                    retryTimerRef.current = setTimeout(() => {
                        if (isMountedRef.current) {
                            executeCore(overrideParams, currentRetry + 1);
                        }
                    }, delay);
                } else {
                    // 重试次数用尽或不可重试的错误
                    if (!isMountedRef.current) {
                        return;
                    }

                    setError(errorObj);
                    setLoading(false);

                    // 失败回调
                    safeCall(onError, errorObj, requestParams);
                    safeCall(onFinally, requestParams, undefined, errorObj);

                    if (pollingInterval && pollingInterval > 0) {
                        pollingTimerRef.current = setTimeout(() => {
                            executeCore(overrideParams, 0);
                        }, pollingInterval);
                    }
                }
            }
        },
        [url]
    );

    // 防抖 / 节流 调度
    const execute = useCallback(
        (overrideParams?: Record<string, any>): Promise<T | undefined> => {
            const { debounceInterval, throttleInterval } = optionsRef.current;

            if (debounceInterval && debounceInterval > 0) {
                return new Promise((resolve) => {
                    if (debounceTimerRef.current) {
                        clearTimeout(debounceTimerRef.current);
                    }
                    debounceTimerRef.current = setTimeout(async () => {
                        const res = await executeCore(overrideParams);
                        resolve(res);
                    }, debounceInterval);
                });
            }

            if (throttleInterval && throttleInterval > 0) {
                return new Promise((resolve) => {
                    if (isThrottledRef.current) {
                        resolve(undefined);
                        return;
                    }

                    isThrottledRef.current = true;
                    throttleTimerRef.current = setTimeout(() => {
                        isThrottledRef.current = false;
                    }, throttleInterval);

                    executeCore(overrideParams).then(resolve);
                });
            }

            return executeCore(overrideParams);
        },
        [executeCore]
    );

    // 挂载/卸载生命周期：仅在真正卸载时置为 false，避免受其它 effect 重跑影响
    useEffect(() => {
        isMountedRef.current = true;
        return () => {
            isMountedRef.current = false;
        };
    }, []);

    // 挂载执行
    useEffect(() => {
        if (immediate) {
            executeCore();
        }

        return () => {
            clearTimers();
            if (abortControllerRef.current) {
                abortControllerRef.current.abort();
            }
        };
    }, [url, immediate, executeCore, clearTimers]);

    // 聚焦重连
    useEffect(() => {
        if (!refreshOnWindowFocus) {
            return;
        }

        const handleFocus = () => {
            if (document.visibilityState === 'visible') {
                executeCore();
            }
        };

        window.addEventListener('visibilitychange', handleFocus);
        window.addEventListener('focus', handleFocus);

        return () => {
            window.removeEventListener('visibilitychange', handleFocus);
            window.removeEventListener('focus', handleFocus);
        };
    }, [refreshOnWindowFocus, executeCore]);

    const abort = useCallback(() => {
        clearTimers();
        if (abortControllerRef.current) {
            abortControllerRef.current.abort();
            abortControllerRef.current = null;
        }
        if (isMountedRef.current) {
            setLoading(false);
        }
    }, [clearTimers]);

    return {
        data,
        loading,
        error,
        refetch: execute,
        run: execute,
        abort,
    };
}
