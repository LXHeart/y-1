package com.grassland.identity.store;

/** #52 门店分配/调度请求：role 为目标店的门店角色（manager/staff）。 */
public record AssignStoreMemberRequest(String role) {
}
