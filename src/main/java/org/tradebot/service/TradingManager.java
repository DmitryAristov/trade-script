package org.tradebot.service;

import org.tradebot.domain.TradingAccountSettings;
import org.tradebot.domain.TradingAccount;
import org.tradebot.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TradingManager {

    private final Log log = new Log();
    private final Map<Integer, TradingAccount> accounts = new ConcurrentHashMap<>();
    private static TradingManager instance;

    public static TradingManager getInstance() {
        if (instance == null) {
            instance = new TradingManager();
        }
        return instance;
    }

    private TradingManager() {  }

    public void addAccount(int clientNumber, TradingAccountSettings settings) {
        try {
            TradingAccount account = new TradingAccount(clientNumber, settings);
            accounts.put(clientNumber, account);
            account.start();
        } catch (Exception e) {
            log.error("Failed to start account.", e);
            log.info("Skipping...");
        }
    }

    public void removeAccount(int clientNumber) {
        try {
            TradingAccount account = accounts.remove(clientNumber);
            if (account != null) {
                account.stop();
            }
        } catch (Exception e) {
            log.info("Unable to stop account " + ". Skipping...");
        }
    }

    public TradingAccount get(int clientNumber) {
        return accounts.get(clientNumber);
    }

    public void stopAll() {
        accounts.forEach((clientNumber, _) -> removeAccount(clientNumber));
    }

    public void logAll() {
        accounts.forEach((_, tradingAccount) -> tradingAccount.logAll());
    }

    public Map<Integer, TradingAccount> getAccounts() {
        return accounts;
    }
}
