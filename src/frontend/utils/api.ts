
const BASE_API_URL = 'http://127.0.0.1:9090';

/**
 * get 请求封装，直接返回后端data字段
 * @param {string} url
 * @param {Object} [params] query参数对象
 * @returns {Promise<any>} 返回后端response.data
 */
export async function getJson(url:string, params:Record<string, any>) {
    let queryUrl = url;
    if (params && typeof params === 'object') {
        const sp = new URLSearchParams(params);
        queryUrl += (url.includes('?') ? '&' : '?') + sp.toString();
    }

    const res = await fetch(`${BASE_API_URL}${queryUrl}`, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        },
        // 如果后端需要cookie凭证
        //credentials: 'include'
    });

    // http层面错误，如404 500
    if (!res.ok) {
        if(res.status === 401){
            // 跳登录页
            location.href = '/login'
            return;
        }
        throw new Error(`HTTP ${res.status}`);
    }

    const json = await res.json();
    // 业务code判断：code !==0 业务失败
    if (json.code !== 0) {
        // 把后端message抛出，上层catch捕获
        throw new Error(json.message || '业务请求失败');
    }

    return json.data;
}

export async function postJson(url:string, body:Record<string, any>) {
    const res = await fetch(`${BASE_API_URL}${url}`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(body)
    });

    const json = await res.json();
    // 业务code判断：code !==0 业务失败
    if (json.code !== 0) {
        // 把后端message抛出，上层catch捕获
        throw new Error(json.message || '业务请求失败');
    }

    return json.data;
}