package com.inmc.shop.config

import com.inmc.shop.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌. 읽고 보내는 부분은 core 의 [MessageCatalog] 가 갖고 있고 여기는 기본값 표뿐이다.
 * `ResourceTest` 가 배포 파일과 이 표의 키가 정확히 같은지, 코드가 부르는 키가 전부 있는지 지킨다. 빈 값 = 그 메시지를 끈다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#56ab2f:#a8e063>[ 상점 ]</gradient> ",

            // --- 공통 ---------------------------------------------------------------
            "player-only" to "<red>플레이어만 쓸 수 있습니다.</red>",
            "player-not-found" to "<red>'{player}' 을(를) 찾을 수 없습니다.</red>",
            "no-permission" to "<red>권한이 없습니다.</red>",
            "module-disabled" to "<red>지금은 쓸 수 없는 기능입니다.</red>",
            "feature-disabled" to "<red>이 기능은 꺼져 있습니다.</red>",
            "world-not-allowed" to "<red>이 월드에서는 상점을 쓸 수 없습니다.</red>",
            "survival-only" to "<red>상점은 서바이벌 모드에서만 쓸 수 있습니다.</red>",
            "busy" to "<yellow>잠시 뒤에 다시 해 주세요.</yellow>",
            "reloaded" to "<green>설정을 다시 불러왔습니다.</green>",
            "restart-needed" to "<yellow>이 설정은 서버를 다시 켜야 반영됩니다.</yellow>",
            "hand-empty" to "<red>손에 아이템을 들고 있어야 합니다.</red>",
            "currency-missing" to "<red>화폐 '{currency}' 을(를) 찾을 수 없습니다. 화폐 플러그인을 확인하세요.</red>",
            "currency-not-allowed" to "<red>{currency}<red> 은(는) 여기서 쓸 수 없는 화폐입니다.</red>",
            "not-enough-money" to "<red>돈이 모자랍니다. 필요: <white>{price}</white></red>",
            "not-enough-items" to "<red>팔 물건이 모자랍니다.</red>",
            "inventory-full" to "<red>가방에 자리가 없습니다.</red>",
            "deposit-failed" to "<red>돈을 넣지 못했습니다(최대 금액 등). 되돌렸습니다.</red>",
            "too-much" to "<red>금액이 너무 큽니다. 수량을 줄여 주세요.</red>",
            "given" to "<green>{player} 에게 {수량}개를 주었습니다.</green>",
            "bad-id" to "<red>쓸 수 없는 이름입니다: {value}</red>",
            "already-exists" to "<red>'{value}' 은(는) 이미 있습니다.</red>",

            // --- 서버 상점 ----------------------------------------------------------
            "unknown-shop" to "<red>'{shop}' 상점이 없습니다.</red>",
            "main-menu-disabled" to "<red>메인 메뉴가 꺼져 있습니다. /상점 <상점> 으로 여세요.</red>",
            "shop-no-permission" to "<red>이 상점을 쓸 권한이 없습니다.</red>",
            "shop-buying-disabled" to "<red>이 상점에서는 살 수 없습니다.</red>",
            "shop-selling-disabled" to "<red>이 상점에서는 팔 수 없습니다.</red>",
            "product-broken" to "<red>이 상품은 설정이 잘못되어 거래할 수 없습니다. 관리자에게 알려 주세요.</red>",
            "product-not-ready" to "<yellow>아직 가격이 정해지지 않은 상품입니다.</yellow>",
            "product-hidden" to "<red>지금은 상점에 없는 상품입니다.</red>",
            "requirements-not-met" to "<red>이 상품을 사고팔 조건이 맞지 않습니다.</red>",
            "buy-disabled" to "<red>이 상품은 살 수 없습니다.</red>",
            "sell-disabled" to "<red>이 상품은 팔 수 없습니다.</red>",
            "limit-reached" to "<red>한도에 닿았습니다. (한도 {max})</red>",
            "out-of-stock" to "<red>재고가 없습니다.</red>",
            "stock-full" to "<red>상점이 더 사지 않습니다(재고가 가득).</red>",
            "bought" to "<green>{물건} <white>x{수량}</white> 을(를) <white>{가격}</white> 에 샀습니다.</green>",
            "sold" to "<green>{물건} <white>x{수량}</white> 을(를) 팔아 <white>{가격}</white> 을(를) 받았습니다.</green>",
            "sell-nothing" to "<yellow>팔 수 있는 것이 없습니다.</yellow>",
            "sold-summary" to "<green><white>{개수}</white>개를 팔아 <white>{가격}</white> 을(를) 받았습니다.</green>",
            "rotation-changed" to "<aqua>{shop}<aqua> 의 상품이 바뀌었습니다!</aqua>",
            "rotation-forced" to "<green>회전을 굴렸습니다.</green>",
            "no-rotations" to "<yellow>이 상점에는 회전이 없습니다.</yellow>",

            // --- 관리 --------------------------------------------------------------
            "admin-picked" to "<gray>골랐습니다. 빈 칸을 누르면 그 자리에 상품이 됩니다.</gray>",
            "admin-pick-first" to "<yellow>먼저 아래 가방의 물건을 클릭해 고르세요.</yellow>",
            "admin-organized" to "<green>상품 칸을 정리했습니다. 숨긴 상품 {개수}개는 맨 뒤로 옮겼습니다.</green>",
            "admin-organize-no-room" to "<red>상품 칸이 모자라 정리하지 못했습니다. 페이지를 늘리세요.</red>",
            "layout-saved" to "<green>레이아웃을 저장했습니다.</green>",
            "defaults-created" to "<green>기본 상점 {개수}개를 만들었습니다.</green>",

            // --- 상자 상점 ----------------------------------------------------------
            "chest-look-at-container" to "<red>상점으로 만들 상자를 바라보고 쓰세요.</red>",
            "chest-not-container" to "<red>이 블록은 상점이 될 수 없습니다.</red>",
            "chest-already" to "<red>이미 상점입니다.</red>",
            "chest-limit" to "<red>상점을 더 만들 수 없습니다. (최대 {max})</red>",
            "chest-no-build" to "<red>여기에는 상점을 만들 수 없습니다(건축 권한).</red>",
            "chest-lands-only" to "<red>자기 땅(Lands) 안에서만 상점을 만들 수 있습니다.</red>",
            "chest-created" to "<green>상자 상점을 만들었습니다! 우클릭해서 상품을 올리세요.</green>",
            "chest-removed" to "<green>상점을 지웠습니다. 창고의 물건은 돌려받았습니다.</green>",
            "chest-break-denied" to "<red>상점은 부술 수 없습니다. 우클릭 → 관리 화면에서 지우세요.</red>",
            "chest-no-merge" to "<red>상점 상자 옆에는 같은 상자를 붙일 수 없습니다.</red>",
            "chest-own-shop" to "<yellow>자기 상점입니다.</yellow>",
            "chest-banned-item" to "<red>이 물건은 상점에 올릴 수 없습니다.</red>",
            "chest-product-limit" to "<red>상품을 더 올릴 수 없습니다. (최대 {max})</red>",
            "chest-product-exists" to "<yellow>이미 올린 물건입니다.</yellow>",
            "chest-product-added" to "<green>{물건}<green> 을(를) 올렸습니다. 가격을 정하세요.</green>",
            "chest-stock-full" to "<red>창고가 가득 찼습니다.</red>",
            "chest-empty" to "<yellow>창고가 비었습니다.</yellow>",
            "chest-deposited" to "<green>창고에 {물건}<green> <white>{수량}</white>개를 넣었습니다.</green>",
            "chest-withdrew" to "<green>창고에서 {물건}<green> <white>{수량}</white>개를 뺐습니다.</green>",
            "chest-out-of-stock" to "<red>이 상점의 재고가 없습니다.</red>",
            "chest-no-money" to "<red>상점 주인이 돈이 모자라 살 수 없습니다.</red>",
            "chest-bought" to "<green>{shop}<green> 에서 {물건} <white>x{수량}</white> 을(를) <white>{가격}</white> 에 샀습니다.</green>",
            "chest-sold" to "<green>{shop}<green> 에 {물건} <white>x{수량}</white> 을(를) 팔아 <white>{가격}</white> 을(를) 받았습니다.</green>",
            "chest-owner-sold-to" to "<gray>[{shop}<gray>] {player} 이(가) {물건} x{수량} 을(를) 샀습니다. +{가격}</gray>",
            "chest-owner-bought-from" to "<gray>[{shop}<gray>] {player} 이(가) {물건} x{수량} 을(를) 팔았습니다. -{가격}</gray>",
            "chest-teleported" to "<green>{shop}<green> 으로 이동했습니다.</green>",
            "chest-teleport-unsafe" to "<red>그 상점 앞이 안전하지 않아 이동하지 않았습니다.</red>",
            "bank-withdrew" to "<green>은행에서 <white>{가격}</white> 을(를) 뺐습니다.</green>",
            "bank-deposited" to "<green>은행에 <white>{가격}</white> 을(를) 넣었습니다.</green>",
            "bank-not-enough" to "<red>은행 잔고가 모자랍니다.</red>",
            "rent-unavailable" to "<red>이 상점은 지금 빌릴 수 없습니다.</red>",
            "rent-too-long" to "<red>최대 {값}일까지만 빌릴 수 있습니다.</red>",
            "rent-started" to "<green>{shop}<green> 을(를) 빌렸습니다. 남은 기간 {시간}. 우클릭해서 상품을 올리세요.</green>",
            "rent-extended" to "<green>임대를 연장했습니다. 남은 기간 {시간}.</green>",
            "rent-owner-notice" to "<gray>{player} 이(가) {shop}<gray> 을(를) 빌렸습니다. +{가격}</gray>",
            "rent-ended" to "<yellow>{shop}<yellow> 임대가 끝났습니다. 남은 물건은 돌려받았습니다(/상자상점 돌려받기).</yellow>",
            "rent-active" to "<red>빌려준 동안에는 지울 수 없습니다. 먼저 임대를 끝내세요.</red>",
            "rent-price-too-high" to "<red>임대료는 최대 {가격} 입니다.</red>",
            "returns-stored" to "<yellow>가방이 차서 {수량}개는 보관했습니다. /상자상점 돌려받기 로 받으세요.</yellow>",
            "returns-claimed" to "<green>돌려받을 물건 {개수}묶음을 받았습니다.</green> <gray>(남음 {값})</gray>",
            "returns-none" to "<gray>돌려받을 물건이 없습니다.</gray>",
            "returns-join" to "<yellow>돌려받을 물건이 {개수}묶음 있습니다. /상자상점 돌려받기</yellow>",

            // --- 경매장 ------------------------------------------------------------
            "auction-bad-price" to "<red>값은 1 이상이어야 합니다.</red>",
            "auction-limit" to "<red>더 올릴 수 없습니다. (최대 {max})</red>",
            "auction-price-bounds" to "<red>이 화폐로는 {값} 사이의 값만 됩니다.</red>",
            "auction-price-bounds-item" to "<red>이 물건은 개당 {값} 사이의 값만 됩니다.</red>",
            "auction-banned-item" to "<red>이 물건은 경매에 올릴 수 없습니다.</red>",
            "auction-banned" to "<red>경고가 쌓여 경매 등록이 금지되었습니다. 남은 기간 {시간}. 사유: {사유}</red>",
            "auction-item-changed" to "<yellow>고른 물건이 가방에서 바뀌었습니다. 다시 골라 주세요.</yellow>",
            "auction-failed" to "<red>올리지 못했습니다. 물건과 수수료를 돌려드렸습니다.</red>",
            "auction-listed" to "<green>{물건} <white>x{수량}</white> 을(를) <white>{가격}</white> 에 올렸습니다. (수수료 {값})</green>",
            "auction-announce" to "<gold>[경매]</gold> <white>{player}</white> 이(가) {물건} <white>x{수량}</white> 을(를) <white>{가격}</white> 에 올렸습니다.",
            "auction-gone" to "<yellow>이미 팔렸거나 내려간 물건입니다.</yellow>",
            "auction-own" to "<yellow>자기 물건은 살 수 없습니다.</yellow>",
            "auction-bought" to "<green>{player} 의 {물건} <white>x{수량}</white> 을(를) <white>{가격}</white> 에 샀습니다.</green>",
            "auction-sold-notice" to "<gold>[경매]</gold> <green>{player} 이(가) {물건} 을(를) 샀습니다. 받을 돈 <white>{가격}</white> — /경매장 미수령</green>",
            "auction-claimed" to "<green>팔린 돈 <white>{가격}</white> 을(를) 받았습니다.</green>",
            "auction-nothing-to-claim" to "<gray>받을 돈이 없습니다.</gray>",
            "auction-returned" to "<green>{물건} <white>x{수량}</white> 을(를) 돌려받았습니다.</green>",
            "auction-join-unclaimed" to "<gold>[경매]</gold> <green>팔린 물건 {개수}건의 돈을 받을 수 있습니다. /경매장 미수령</green>",
            "auction-join-expired" to "<gold>[경매]</gold> <yellow>돌려받을 물건 {개수}건이 있습니다. /경매장 만료</yellow>",
            "auction-admin-removed" to "<green>{player} 의 등록을 내렸습니다.</green>",
            "auction-admin-warned" to "<green>{player} 의 등록을 내리고 경고했습니다. (경고 {개수}/{max})</green>",
            "auction-admin-banned" to "<green>{player} 의 등록을 내리고 경고했습니다 — 경고가 {max}회라 {시간} 등록 금지.</green>",
            "auction-removed-notice" to "<gold>[경매]</gold> <yellow>관리자가 내 등록을 내렸습니다. 물건은 /경매장 만료 에서 돌려받으세요.</yellow>",
            "auction-warned-notice" to "<gold>[경매]</gold> <red>관리자 경고({개수}/{max}): {사유}. 물건은 /경매장 만료 에서 돌려받으세요.</red>",
            "auction-banned-notice" to "<gold>[경매]</gold> <red>경고가 {max}회 쌓여 {시간} 동안 경매 등록이 금지되었습니다. 사유: {사유}</red>",
            "auction-warnings-cleared" to "<green>{player} 의 경고와 금지를 지웠습니다.</green>",

            // --- 검증 --------------------------------------------------------------
            "verify-started" to "<light_purple>상점 검증을 시작합니다 — 끝날 때까지 움직이지 마세요. 가방과 잔고는 끝나면 되돌립니다.</light_purple>",
            "verify-finished" to "<light_purple>검증 끝 — 통과 <green>{개수}</green> · 실패 <red>{값}</red> · 건너뜀 {max}. 보고서: {사유}</light_purple>",
            "verify-running" to "<yellow>이미 검증 중입니다.</yellow>",
        )
    }
}
