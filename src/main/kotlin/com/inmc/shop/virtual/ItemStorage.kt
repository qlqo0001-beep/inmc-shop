package com.inmc.shop.virtual

import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StorageMode
import kr.inmc.core.item.StoredItem

/**
 * 상품 아이템의 저장 방식 — 아이템 등록 시스템(인벤키퍼·낚시)과 같은 core [StorageMode].
 * 동적은 원본 정의에서 매번 다시 만들고(커스텀아이템을 고치면 상품도 바뀐다), 스냅샷은 등록한 순간의 모습을 준다.
 *
 * 고를 수 있는 것은 **원본 정의가 따로 있는 아이템**(커스텀아이템·MMOItems)뿐이다. 평범한 바닐라는 두 방식이 같은 것을 만들고,
 * 이름·설명을 손으로 붙인 아이템은 참조가 없어 스냅샷밖에 없다 — 그 둘에 스위치를 보여주면 눌러도 아무 일이 없다.
 */
object ItemStorage {

    enum class Choice { SAME, FIXED, CHOOSABLE }

    fun choice(item: StoredItem): Choice = when (item.ref) {
        is ItemRef.Vanilla -> Choice.SAME
        ItemRef.None -> Choice.FIXED
        else -> Choice.CHOOSABLE
    }

    fun label(item: StoredItem): String = when (choice(item)) {
        Choice.SAME -> "바닐라(방식 무관)"
        Choice.FIXED -> "스냅샷(원본 없음)"
        Choice.CHOOSABLE -> if (item.mode == StorageMode.SNAPSHOT) "스냅샷(고정)" else "동적(참조)"
    }

    /** 아이템을 바꿔 등록해도 관리자가 고른 방식은 남긴다. */
    fun keepMode(old: StoredItem?, new: StoredItem): StoredItem =
        if (old != null && choice(new) == Choice.CHOOSABLE) new.withMode(old.mode) else new
}
