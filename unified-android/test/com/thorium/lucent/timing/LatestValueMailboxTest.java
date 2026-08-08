package com.thorium.lucent.timing;

import com.thorium.lucent.TestSupport;

public final class LatestValueMailboxTest {
    public static void main(String[] ignored) throws Exception {
        replacesAnUnconsumedValue();
        closeUnblocksAndRejectsOffers();
        System.out.println("LatestValueMailboxTest passed");
    }

    private static void replacesAnUnconsumedValue() throws Exception {
        LatestValueMailbox<String> mailbox = new LatestValueMailbox<>();
        TestSupport.truth(mailbox.isEmpty(), "new mailbox is empty");
        TestSupport.truth(mailbox.offer("old"), "first offer accepted");
        TestSupport.truth(mailbox.offer("latest"), "replacement offer accepted");
        TestSupport.equal("latest", mailbox.take(),
                "a lagging renderer receives only the newest frame");
        TestSupport.truth(mailbox.isEmpty(), "take drains the one slot");
    }

    private static void closeUnblocksAndRejectsOffers() throws Exception {
        LatestValueMailbox<String> mailbox = new LatestValueMailbox<>();
        mailbox.offer("stale");
        mailbox.close();
        TestSupport.equal(null, mailbox.take(), "closed mailbox returns no stale value");
        TestSupport.truth(!mailbox.offer("late"), "closed mailbox rejects producers");
    }
}
