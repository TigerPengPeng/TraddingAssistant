package com.autotrading.account;

import com.autotrading.config.FutuProperties;
import com.autotrading.futu.AsyncRequestBridge;
import com.autotrading.futu.FutuConnectionManager;
import com.autotrading.model.StockInfo;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotGetUserSecurityGroup;
import com.futu.openapi.pb.QotModifyUserSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetches user stock groups and stock lists from Futu OpenD.
 */
@Service
public class StockGroupService {

    private static final Logger log = LoggerFactory.getLogger(StockGroupService.class);

    private final FutuConnectionManager connectionManager;
    private final AsyncRequestBridge bridge;
    private final FutuProperties properties;

    public StockGroupService(FutuConnectionManager connectionManager, AsyncRequestBridge bridge,
                              FutuProperties properties) {
        this.connectionManager = connectionManager;
        this.bridge = bridge;
        this.properties = properties;
    }

    /**
     * Fetches all user stock group names.
     */
    public List<String> getGroupNames() throws AsyncRequestBridge.FutuRequestException {
        FTAPI_Conn_Qot conn = connectionManager.getConnQot();
        if (conn == null) {
            throw new AsyncRequestBridge.FutuRequestException("Not connected to OpenD");
        }

        QotGetUserSecurityGroup.Request request = QotGetUserSecurityGroup.Request.newBuilder()
                .setC2S(QotGetUserSecurityGroup.C2S.newBuilder()
                        .setGroupType(QotGetUserSecurityGroup.GroupType.GroupType_All_VALUE)
                        .build())
                .build();

        int serial = conn.getUserSecurityGroup(request);
        QotGetUserSecurityGroup.Response response = bridge.await(serial, QotGetUserSecurityGroup.Response.class);

        if (response.getRetType() != Common.RetType.RetType_Succeed_VALUE) {
            throw new AsyncRequestBridge.FutuRequestException("GetUserSecurityGroup failed: " + response.getRetMsg());
        }

        List<String> names = new ArrayList<>();
        for (QotGetUserSecurityGroup.GroupData group : response.getS2C().getGroupListList()) {
            names.add(group.getGroupName());
        }
        log.info("Found {} stock groups: {}", names.size(), names);
        return names;
    }

    /**
     * Fetches the stock list for a specific group name.
     */
    public List<StockInfo> getStocksInGroup(String groupName) throws AsyncRequestBridge.FutuRequestException {
        FTAPI_Conn_Qot conn = connectionManager.getConnQot();
        if (conn == null) {
            throw new AsyncRequestBridge.FutuRequestException("Not connected to OpenD");
        }

        QotGetUserSecurity.Request request = QotGetUserSecurity.Request.newBuilder()
                .setC2S(QotGetUserSecurity.C2S.newBuilder()
                        .setGroupName(groupName)
                        .build())
                .build();

        int serial = conn.getUserSecurity(request);
        QotGetUserSecurity.Response response = bridge.await(serial, QotGetUserSecurity.Response.class);

        if (response.getRetType() != Common.RetType.RetType_Succeed_VALUE) {
            throw new AsyncRequestBridge.FutuRequestException("GetUserSecurity failed: " + response.getRetMsg());
        }

        List<StockInfo> stocks = new ArrayList<>();
        for (QotCommon.SecurityStaticInfo info : response.getS2C().getStaticInfoListList()) {
            if (info.hasBasic()) {
                QotCommon.SecurityStaticBasic basic = info.getBasic();
                if (basic.hasSecurity()) {
                    int market = basic.getSecurity().getMarket();
                    String code = basic.getSecurity().getCode();
                    String name = basic.hasName() ? basic.getName() : code;
                    stocks.add(new StockInfo(market, code, name));
                }
            }
        }
       log.info("Group [{}] contains {} stocks", groupName, stocks.size());
       return stocks;
   }
    /**
     * Adds the given stocks to a user-security group via QotModifyUserSecurity.
     * Idempotent on Futu's side: adding an already-present stock is a no-op,
     * not an error. Throws FutuRequestException on OpenD failure so the
     * controller can map it to a per-group failure result.
     */
    public void addStocksToGroup(String groupName, List<StockInfo> stocks)
            throws AsyncRequestBridge.FutuRequestException {
        if (stocks == null || stocks.isEmpty()) {
            return;
        }
        FTAPI_Conn_Qot conn = connectionManager.getConnQot();
        if (conn == null) {
            throw new AsyncRequestBridge.FutuRequestException("Not connected to OpenD");
        }

        QotModifyUserSecurity.C2S c2s = QotModifyUserSecurity.C2S.newBuilder()
                .setGroupName(groupName)
                .setOp(QotModifyUserSecurity.ModifyUserSecurityOp.ModifyUserSecurityOp_Add_VALUE)
                .addAllSecurityList(stocks.stream()
                        .map(stk -> QotCommon.Security.newBuilder()
                                .setMarket(stk.getMarket())
                                .setCode(stk.getCode())
                                .build())
                        .toList())
                .build();
        QotModifyUserSecurity.Request request = QotModifyUserSecurity.Request.newBuilder()
                .setC2S(c2s)
                .build();

        int serial = conn.modifyUserSecurity(request);
        QotModifyUserSecurity.Response response = bridge.await(serial, QotModifyUserSecurity.Response.class);

        if (response.getRetType() != Common.RetType.RetType_Succeed_VALUE) {
            throw new AsyncRequestBridge.FutuRequestException(
                    "ModifyUserSecurity(Add) failed for group [" + groupName + "]: " + response.getRetMsg());
        }
        log.info("Added {} stock(s) to group [{}]", stocks.size(), groupName);
    }

    /**
     * Resolves the target stock list based on configured group name.
     * If group-name is empty, uses the first available group.
     */
    public List<StockInfo> resolveTargetStocks() throws AsyncRequestBridge.FutuRequestException {
        String groupName = properties.getFilter().getGroupName();
        if (groupName == null || groupName.isBlank()) {
            List<String> groups = getGroupNames();
            if (groups.isEmpty()) {
                log.warn("No stock groups found in account");
                return List.of();
            }
            groupName = groups.get(0);
            log.info("No group name configured, using first group: {}", groupName);
        }
        return getStocksInGroup(groupName);
    }
}
