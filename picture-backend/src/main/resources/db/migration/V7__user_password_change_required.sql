-- 首次登录强制改密标记。
-- 新注册、管理员创建、管理员重置密码的账号置 1；用户自行修改密码后置 0。
-- 存量账号默认为 0，不会被强制跳转，避免影响已有用户。
ALTER TABLE user
    ADD COLUMN passwordChangeRequired tinyint default 0 not null comment '是否必须修改初始密码：0-否 1-是';
