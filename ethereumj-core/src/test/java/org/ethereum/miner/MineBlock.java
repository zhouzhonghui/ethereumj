package org.ethereum.miner;

import org.ethereum.config.Constants;
import org.ethereum.core.*;
import org.ethereum.core.genesis.GenesisLoader;
import org.ethereum.crypto.ECKey;
import org.ethereum.datasource.HashMapDB;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.db.IndexedBlockStore;
import org.ethereum.db.RepositoryImpl;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.manager.AdminInfo;
import org.ethereum.mine.Ethash;
import org.ethereum.util.ByteUtil;
import org.ethereum.validator.DependentBlockHeaderRuleAdapter;
import org.ethereum.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Created by Anton Nashatyrev on 10.12.2015.
 */
public class MineBlock {

    @BeforeClass
    public static void setup() {
        Constants.MINIMUM_DIFFICULTY = BigInteger.valueOf(1);
    }

    @Test
    public void mine1() throws Exception {
        BlockchainImpl blockchain = createBlockchain(GenesisLoader.loadGenesis(
                ClassLoader.getSystemResourceAsStream("genesis/genesis-light.json")));
        blockchain.setMinerCoinbase(Hex.decode("ee0250c19ad59305b2bdb61f34b45b72fe37154f"));
        Block parent = blockchain.getBestBlock();

        List<Transaction> pendingTx = new ArrayList<>();

        ECKey senderKey = ECKey.fromPrivate(Hex.decode("3ec771c31cac8c0dba77a69e503765701d3c2bb62435888d4ffa38fed60c445c"));
        byte[] receiverAddr = Hex.decode("31e2e1ed11951c7091dfba62cd4b7145e947219c");
        Transaction tx = new Transaction(new byte[] {0}, new byte[] {1}, ByteUtil.longToBytesNoLeadZeroes(0xfffff),
                receiverAddr, new byte[] {77}, new byte[0]);
        tx.sign(senderKey.getPrivKeyBytes());
        pendingTx.add(tx);

        Block b = blockchain.createNewBlock(parent, pendingTx);

        System.out.println("Mining...");
        Ethash.getForBlock(b.getNumber()).mineLight(b).get();
        System.out.println("Validating...");
        boolean valid = Ethash.getForBlock(b.getNumber()).validate(b.getHeader());
        Assert.assertTrue(valid);

        System.out.println("Connecting...");
        ImportResult importResult = blockchain.tryToConnect(b);

        Assert.assertTrue(importResult == ImportResult.IMPORTED_BEST);
        System.out.println(Hex.toHexString(blockchain.getRepository().getRoot()));
    }

    @Test
    public void createFork() throws Exception {
        BlockchainImpl blockchain = createBlockchain(GenesisLoader.loadGenesis(
                ClassLoader.getSystemResourceAsStream("genesis/genesis-light.json")));
        blockchain.setMinerCoinbase(Hex.decode("ee0250c19ad59305b2bdb61f34b45b72fe37154f"));
        Block parent = blockchain.getBestBlock();

        System.out.println("Mining #1 ...");
        Block b1 = blockchain.createNewBlock(parent, Collections.EMPTY_LIST);
        Ethash.getForBlock(b1.getNumber()).mineLight(b1).get();
        ImportResult importResult = blockchain.tryToConnect(b1);
        Assert.assertTrue(importResult == ImportResult.IMPORTED_BEST);

        System.out.println("Mining #2 ...");
        Block b2 = blockchain.createNewBlock(b1, Collections.EMPTY_LIST);
        Ethash.getForBlock(b2.getNumber()).mineLight(b2).get();
        importResult = blockchain.tryToConnect(b2);
        Assert.assertTrue(importResult == ImportResult.IMPORTED_BEST);

        System.out.println("Mining #3 ...");
        Block b3 = blockchain.createNewBlock(b2, Collections.EMPTY_LIST);
        Ethash.getForBlock(b3.getNumber()).mineLight(b3).get();
        importResult = blockchain.tryToConnect(b3);
        Assert.assertTrue(importResult == ImportResult.IMPORTED_BEST);

        System.out.println("Mining #2' ...");
        Block b2_ = blockchain.createNewBlock(b1, Collections.EMPTY_LIST);
        b2_.setExtraData(new byte[] {77, 77}); // setting extra data to differ from block #2
        Ethash.getForBlock(b2_.getNumber()).mineLight(b2_).get();
        importResult = blockchain.tryToConnect(b2_);
        Assert.assertTrue(importResult == ImportResult.IMPORTED_NOT_BEST);

        System.out.println("Mining #3' ...");
        Block b3_ = blockchain.createNewBlock(b2_, Collections.EMPTY_LIST);
        Ethash.getForBlock(b3_.getNumber()).mineLight(b3_).get();
        importResult = blockchain.tryToConnect(b3_);
        Assert.assertTrue(importResult == ImportResult.IMPORTED_NOT_BEST);
    }

    private BlockchainImpl createBlockchain(Genesis genesis) {
        IndexedBlockStore blockStore = new IndexedBlockStore();
        blockStore.init(new HashMap<Long, List<IndexedBlockStore.BlockInfo>>(), new HashMapDB(), null, null);

        Repository repository = new RepositoryImpl(new HashMapDB(), new HashMapDB());

        ProgramInvokeFactoryImpl programInvokeFactory = new ProgramInvokeFactoryImpl();
        EthereumListenerAdapter listener = new EthereumListenerAdapter();

        BlockchainImpl blockchain = new BlockchainImpl(
                blockStore,
                repository,
                new Wallet(),
                new AdminInfo(),
                listener
        );
        blockchain.setParentHeaderValidator(new DependentBlockHeaderRuleAdapter());
        blockchain.setProgramInvokeFactory(programInvokeFactory);
        programInvokeFactory.setBlockchain(blockchain);

        blockchain.byTest = true;

        PendingStateImpl pendingState = new PendingStateImpl(
                listener,
                repository,
                blockStore,
                programInvokeFactory
        );

        pendingState.init();

        pendingState.setBlockchain(blockchain);
        blockchain.setPendingState(pendingState);

        Repository track = repository.startTracking();
        for (ByteArrayWrapper key : genesis.getPremine().keySet()) {
            track.createAccount(key.getData());
            track.addBalance(key.getData(), genesis.getPremine().get(key).getBalance());
        }

        track.commit();

        blockStore.saveBlock(genesis, genesis.getCumulativeDifficulty(), true);

        blockchain.setBestBlock(genesis);
        blockchain.setTotalDifficulty(genesis.getCumulativeDifficulty());

        return blockchain;
    }
}
