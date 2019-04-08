package com.finplant.ib.utils;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Order;

@SuppressWarnings({"WeakerAccess", "unused"})
public class PrettyPrinters {

    static public String contractToString(Contract contract) {
        final StringBuilder buffer = new StringBuilder("{");
        buffer.append("conid=").append(contract.conid());
        buffer.append(", symbol=").append(contract.symbol());
        buffer.append(", currency=").append(contract.currency());
        buffer.append(", exchange=").append(contract.exchange());
        buffer.append(", multiplier=").append(contract.multiplier());
        buffer.append(", lastTradeDateOrContractMonth=").append(contract.lastTradeDateOrContractMonth());
        buffer.append(", localSymbol=").append(contract.localSymbol());
        buffer.append("}");
        return buffer.toString();
    }

    static public String orderToString(Order order) {

        final StringBuilder buffer = new StringBuilder("{");
        buffer.append("id=").append(order.orderId());
        buffer.append(", action=").append(order.action());

        if (order.orderType() != null) {
            buffer.append(", orderType=").append(order.orderType());
        }
        if (order.auxPrice() != Double.MAX_VALUE) {
            buffer.append(", auxPrice=").append(order.auxPrice());
        }
        if (order.lmtPrice() != Double.MAX_VALUE) {
            buffer.append(", lmtPrice=").append(order.lmtPrice());
        }
        if (order.tif() != null) {
            buffer.append(", tif=").append(order.tif());
        }

        buffer.append(", totalQuantity=").append(order.totalQuantity());
        buffer.append(", outsideRth=").append(order.outsideRth());
        buffer.append("}");
        return buffer.toString();
    }

    public static String contractDetailsToString(ContractDetails details) {
        final StringBuilder buffer = new StringBuilder("{");
        buffer.append("contract=[").append(contractToString(details.contract())).append("]");
        buffer.append(", conid=").append(details.conid());
        buffer.append(", minTick=").append(details.minTick());
        buffer.append(", validExchanges=").append(details.validExchanges());
        return buffer.toString();
    }
}
