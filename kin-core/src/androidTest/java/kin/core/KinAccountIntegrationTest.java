package kin.core;


import static junit.framework.Assert.fail;
import static kin.core.IntegConsts.TEST_NETWORK_ID;
import static kin.core.IntegConsts.TEST_NETWORK_URL;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import kin.core.exception.AccountNotActivatedException;
import kin.core.exception.AccountNotFoundException;
import kin.core.exception.InsufficientKinException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.stellar.sdk.Memo;
import org.stellar.sdk.MemoText;
import org.stellar.sdk.Server;
import org.stellar.sdk.responses.TransactionResponse;

@SuppressWarnings({"deprecation", "ConstantConditions"})
public class KinAccountIntegrationTest {

    private static FakeKinIssuer fakeKinIssuer;
    private KinClient kinClient;

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @BeforeClass
    static public void setupKinIssuer() throws IOException {
        fakeKinIssuer = new FakeKinIssuer();
    }

    private class TestServiceProvider extends ServiceProvider {

        TestServiceProvider() {
            super(TEST_NETWORK_URL, TEST_NETWORK_ID);
        }

        @Override
        protected String getAssetCode() {
            return "KIN";
        }

        @Override
        protected String getIssuerAccountId() {
            return fakeKinIssuer.getAccountId();
        }
    }


    @Before
    public void setup() throws IOException {
        ServiceProvider serviceProvider = new TestServiceProvider();
        kinClient = new KinClient(InstrumentationRegistry.getTargetContext(), serviceProvider);
        kinClient.clearAllAccounts();
    }

    @After
    public void teardown() {
        kinClient.clearAllAccounts();
    }

    @Test
    @LargeTest
    public void getBalanceSync_AccountNotCreated_AccountNotFoundException() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();

        expectedEx.expect(AccountNotFoundException.class);
        expectedEx.expectMessage(kinAccount.getPublicAddress());
        kinAccount.getBalanceSync();
    }

    @Test
    @LargeTest
    public void getStatusSync_AccountNotCreated_StatusNotCreated() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();

        int status = kinAccount.getStatusSync();
        assertThat(status, equalTo(AccountStatus.NOT_CREATED));
    }

    @Test
    @LargeTest
    public void getBalanceSync_AccountNotActivated_AccountNotActivatedException() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccount.getPublicAddress());

        expectedEx.expect(AccountNotActivatedException.class);
        expectedEx.expectMessage(kinAccount.getPublicAddress());
        kinAccount.getBalanceSync();
    }

    @Test
    @LargeTest
    public void getStatusSync_AccountNotActivated_StatusNotActivated() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccount.getPublicAddress());

        int status = kinAccount.getStatusSync();
        assertThat(status, equalTo(AccountStatus.NOT_ACTIVATED));
    }

    @Test
    @LargeTest
    public void getBalanceSync_FundedAccount_GotBalance() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccount.getPublicAddress());

        kinAccount.activateSync();
        assertThat(kinAccount.getBalanceSync().value(), equalTo(new BigDecimal("0.0000000")));

        fakeKinIssuer.fundWithKin(kinAccount.getPublicAddress(), "3.1415926");
        assertThat(kinAccount.getBalanceSync().value(), equalTo(new BigDecimal("3.1415926")));
    }

    @Test
    @LargeTest
    public void getStatusSync_CreateAndActivateAccount_StatusActivated() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccount.getPublicAddress());

        kinAccount.activateSync();
        assertThat(kinAccount.getBalanceSync().value(), equalTo(new BigDecimal("0.0000000")));
        int status = kinAccount.getStatusSync();
        assertThat(status, equalTo(AccountStatus.ACTIVATED));
    }

    @Test
    @LargeTest
    public void activateSync_AccountNotCreated_AccountNotFoundException() throws Exception {
        KinAccount kinAccount = kinClient.addAccount();

        expectedEx.expect(AccountNotFoundException.class);
        expectedEx.expectMessage(kinAccount.getPublicAddress());
        kinAccount.activateSync();
    }

    @Test
    @LargeTest
    public void sendTransaction() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountSender.activateSync();
        kinAccountReceiver.activateSync();
        fakeKinIssuer.fundWithKin(kinAccountSender.getPublicAddress(), "100");

        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
        assertThat(kinAccountSender.getBalanceSync().value(), equalTo(new BigDecimal("78.8770000")));
        assertThat(kinAccountReceiver.getBalanceSync().value(), equalTo(new BigDecimal("21.1230000")));
    }

    @Test
    @LargeTest
    public void sendTransaction_WithMemo() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());
        String expectedMemo = "fake memo";

        kinAccountSender.activateSync();
        kinAccountReceiver.activateSync();
        fakeKinIssuer.fundWithKin(kinAccountSender.getPublicAddress(), "100");

        TransactionId transactionId = kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"),
                expectedMemo);
        assertThat(kinAccountSender.getBalanceSync().value(), equalTo(new BigDecimal("78.8770000")));
        assertThat(kinAccountReceiver.getBalanceSync().value(), equalTo(new BigDecimal("21.1230000")));

        Server server = new Server(TEST_NETWORK_URL);
        TransactionResponse transaction = server.transactions().transaction(transactionId.id());
        Memo actualMemo = transaction.getMemo();
        assertThat(actualMemo, is(instanceOf(MemoText.class)));
        assertThat(((MemoText) actualMemo).getText(), equalTo(expectedMemo));
    }

    @Test
    @LargeTest
    public void sendTransaction_ReceiverAccountNotCreated_AccountNotFoundException() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());

        expectedEx.expect(AccountNotFoundException.class);
        expectedEx.expectMessage(kinAccountReceiver.getPublicAddress());
        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
    }

    @Test
    @LargeTest
    public void sendTransaction_SenderAccountNotCreated_AccountNotFoundException() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());
        kinAccountReceiver.activateSync();

        expectedEx.expect(AccountNotFoundException.class);
        expectedEx.expectMessage(kinAccountSender.getPublicAddress());
        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
    }

    @Test
    @LargeTest
    public void sendTransaction_ReceiverAccountNotActivated_AccountNotFoundException() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountSender.activateSync();

        expectedEx.expect(AccountNotActivatedException.class);
        expectedEx.expectMessage(kinAccountReceiver.getPublicAddress());
        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
    }

    @Test
    @LargeTest
    public void sendTransaction_SenderAccountNotActivated_AccountNotFoundException() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountReceiver.activateSync();

        expectedEx.expect(AccountNotActivatedException.class);
        expectedEx.expectMessage(kinAccountSender.getPublicAddress());
        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
    }

    @Test
    @LargeTest
    public void createPaymentListener_ListenToReceiver_PaymentEvent() throws Exception {
        listenToPayments(false);
    }

    @Test
    @LargeTest
    public void createPaymentListener_ListenToSender_PaymentEvent() throws Exception {
        listenToPayments(true);
    }

    private void listenToPayments(boolean sender) throws Exception {
        //create and sets 2 accounts (receiver/sender), fund one account, and then
        //send transaction from the funded account to the other, observe this transaction using listeners
        BigDecimal fundingAmount = new BigDecimal("100");
        BigDecimal transactionAmount = new BigDecimal("21.123");

        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountSender.activateSync();
        kinAccountReceiver.activateSync();

        //register listeners for testing
        final List<PaymentInfo> actualPaymentsResults = new ArrayList<>();
        final List<Balance> actualBalanceResults = new ArrayList<>();
        KinAccount accountToListen = sender ? kinAccountSender : kinAccountReceiver;

        int eventsCount = sender ? 4 : 2; ///in case of observing the sender we'll get 2 events (1 for funding 1 for the
        //transaction) in case of receiver - only 1 event.
        //multiply by 2, as we 2 listeners (balance and payment)
        final CountDownLatch latch = new CountDownLatch(eventsCount);
        accountToListen.blockchainEvents().addPaymentListener(new EventListener<PaymentInfo>() {
            @Override
            public void onEvent(PaymentInfo data) {
                actualPaymentsResults.add(data);
                latch.countDown();
            }
        });
        accountToListen.blockchainEvents().addBalanceListener(new EventListener<Balance>() {
            @Override
            public void onEvent(Balance data) {
                actualBalanceResults.add(data);
                latch.countDown();
            }
        });

        //send the transaction we want to observe
        fakeKinIssuer.fundWithKin(kinAccountSender.getPublicAddress(), "100");
        String expectedMemo = "memo";
        TransactionId expectedTransactionId = kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), transactionAmount, expectedMemo);

        //verify data notified by listeners
        int transactionIndex =
            sender ? 1 : 0; //in case of observing the sender we'll get 2 events (1 for funding 1 for the
        //transaction) in case of receiver - only 1 event
        latch.await(10, TimeUnit.SECONDS);
        PaymentInfo paymentInfo = actualPaymentsResults.get(transactionIndex);
        assertThat(paymentInfo.amount(), equalTo(transactionAmount));
        assertThat(paymentInfo.destinationPublicKey(), equalTo(kinAccountReceiver.getPublicAddress()));
        assertThat(paymentInfo.sourcePublicKey(), equalTo(kinAccountSender.getPublicAddress()));
        assertThat(paymentInfo.memo(), equalTo(expectedMemo));
        assertThat(paymentInfo.hash().id(), equalTo(expectedTransactionId.id()));

        Balance balance = actualBalanceResults.get(transactionIndex);
        assertThat(balance.value(),
            equalTo(sender ? fundingAmount.subtract(transactionAmount) : transactionAmount));
    }

    @Test
    @LargeTest
    public void createPaymentListener_RemoveListener_NoEvents() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountSender.activateSync();
        kinAccountReceiver.activateSync();
        fakeKinIssuer.fundWithKin(kinAccountSender.getPublicAddress(), "100");

        final CountDownLatch latch = new CountDownLatch(1);

        BlockchainEvents blockchainEvents = kinAccountReceiver.blockchainEvents();
        ListenerRegistration listenerRegistration = blockchainEvents
            .addPaymentListener(new EventListener<PaymentInfo>() {
                @Override
                public void onEvent(PaymentInfo data) {
                    fail("should not get eny event!");
                    latch.countDown();
                }
            });
        listenerRegistration.remove();

        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"), null);

        latch.await(15, TimeUnit.SECONDS);
    }

    @Test
    @LargeTest
    public void sendTransaction_NotEnoughKin_TransactionFailedException() throws Exception {
        KinAccount kinAccountSender = kinClient.addAccount();
        KinAccount kinAccountReceiver = kinClient.addAccount();
        fakeKinIssuer.createAccount(kinAccountSender.getPublicAddress());
        fakeKinIssuer.createAccount(kinAccountReceiver.getPublicAddress());

        kinAccountSender.activateSync();
        kinAccountReceiver.activateSync();

        expectedEx.expect(InsufficientKinException.class);
        kinAccountSender
            .sendTransactionSync(kinAccountReceiver.getPublicAddress(), new BigDecimal("21.123"));
    }

}
