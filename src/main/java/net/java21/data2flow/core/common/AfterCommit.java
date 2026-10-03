package net.java21.data2flow.core.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 커밋된 뒤에 할 일(메일 발송 등). 트랜잭션이 없으면 바로 실행한다.
 * 실패는 업무 결과를 바꾸지 않고 경고 로그로만 남긴다(메일은 재발송으로 복구한다).
 */
public final class AfterCommit {

    private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

    private AfterCommit() {
    }

    public static void run(String what, Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            safely(what, task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safely(what, task);
            }
        });
    }

    private static void safely(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException ex) {
            log.warn("커밋 후 작업 실패: {}", what, ex);
        }
    }
}
