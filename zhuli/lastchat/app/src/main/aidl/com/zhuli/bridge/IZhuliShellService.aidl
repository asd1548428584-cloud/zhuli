package com.zhuli.bridge;

// Shizuku 用户服务协议：destroy() 必须带 id 16777114（transact 约定）；其余方法统一编号
interface IZhuliShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}
