package com.controlexo.app.csv

import org.junit.Test

/**
 * 手动核对工具：用 docs/book 下的真实账单文件打印解析结果（文件不入库，缺席时自动跳过）。
 * 微信/支付宝下次改版时，把新导出文件放进 docs/book 运行本类核对。
 * 运行：gradle :app:testDebugUnitTest --tests "*RealFileParseCheck*"
 */
class RealFileParseCheck {

    @Test
    fun `dump real wechat xlsx parse result`() {
        val file = java.io.File("../docs/book/微信支付账单流水文件(20260704-20261004)_20261004234212..xlsx")
        if (!file.exists()) return
        val bill = BillParsers.parse(file.readBytes())
        println("WECHAT source=${bill?.source}")
        println("WECHAT rows=${bill?.rows?.size} skipType=${bill?.skippedOtherType} skipStatus=${bill?.skippedStatus}")
        bill?.rows?.take(5)?.forEach {
            println("  ${it.timestamp} ${it.type} ${it.amountCents} [${it.counterparty}] [${it.goods}] cat=${it.guessedCategoryName} id=${it.externalId?.take(12)}")
        }
        println("WECHAT last rows:")
        bill?.rows?.takeLast(3)?.forEach {
            println("  ${it.timestamp} ${it.type} ${it.amountCents} [${it.counterparty}] [${it.goods}] cat=${it.guessedCategoryName}")
        }
    }

    @Test
    fun `dump real alipay csv parse result`() {
        val file = java.io.File("../docs/book/支付宝交易明细(20260704-20261004)..csv")
        if (!file.exists()) return
        val bill = BillParsers.parse(file.readBytes())
        println("ALIPAY source=${bill?.source}")
        println("ALIPAY rows=${bill?.rows?.size} skipType=${bill?.skippedOtherType} skipStatus=${bill?.skippedStatus}")
        bill?.rows?.take(5)?.forEach {
            println("  ${it.timestamp} ${it.type} ${it.amountCents} [${it.counterparty}] [${it.goods}] cat=${it.guessedCategoryName} pay=${it.payMethod} id=${it.externalId?.take(12)}")
        }
        println("ALIPAY last rows:")
        bill?.rows?.takeLast(3)?.forEach {
            println("  ${it.timestamp} ${it.type} ${it.amountCents} [${it.counterparty}] [${it.goods}] cat=${it.guessedCategoryName}")
        }
    }
}
