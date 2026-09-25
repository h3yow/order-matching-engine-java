package com.github.h3yow.ordermatchingenginejava.account;

import com.github.h3yow.ordermatchingenginejava.risk.RiskLimits;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 账户注册与查询。具体冻结/结算逻辑由撮合引擎在持有引擎锁时调用。 */
@Service
public class AccountService {

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final RiskLimits limits;

    public AccountService(RiskLimits limits) {
        this.limits = limits;
    }

    /** 获取账户，不存在则以默认资金/持仓创建（模拟本地账户体系）。 */
    public Account getOrCreate(String accountId) {
        return accounts.computeIfAbsent(accountId,
                id -> new Account(id, limits.getDefaultCash(), limits.getDefaultPosition()));
    }

    public Account get(String accountId) {
        return accounts.get(accountId);
    }
}
