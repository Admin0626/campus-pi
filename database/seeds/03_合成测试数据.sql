-- 仅限新建的测试空库 不可用于现有业务库
-- 所有姓名学号均为合成数据 密码仅用于本次验证
INSERT INTO users(user_id,username,password_hash,role,campus,student_no,real_name,identity_verification,campus_verification) VALUES
(1,'test_admin','$argon2id$v=19$m=65536,t=3,p=1$hsqQhUzmww1AwZt4YhudlA$tEOa5oKG6lfnecpYNyreBXxleoL+iHEptEkawirZyT4','ADMIN','东校区','T001','测试管理员','已通过','已通过'),
(2,'test_publisher','$argon2id$v=19$m=65536,t=3,p=1$hsqQhUzmww1AwZt4YhudlA$tEOa5oKG6lfnecpYNyreBXxleoL+iHEptEkawirZyT4','USER','东校区','T002','测试发布者','已通过','已通过'),
(3,'test_taker','$argon2id$v=19$m=65536,t=3,p=1$hsqQhUzmww1AwZt4YhudlA$tEOa5oKG6lfnecpYNyreBXxleoL+iHEptEkawirZyT4','USER','东校区','T003','测试接取者','已通过','已通过'),
(4,'test_other','$argon2id$v=19$m=65536,t=3,p=1$hsqQhUzmww1AwZt4YhudlA$tEOa5oKG6lfnecpYNyreBXxleoL+iHEptEkawirZyT4','USER','西校区','T004','测试其他人','已通过','已通过');
INSERT INTO users(user_id,username,password_hash) VALUES(5,'test_unverified','$argon2id$v=19$m=65536,t=3,p=1$hsqQhUzmww1AwZt4YhudlA$tEOa5oKG6lfnecpYNyreBXxleoL+iHEptEkawirZyT4');
INSERT INTO reward_accounts(user_id,reward_type,available_amount) VALUES(2,'积分',1000),(2,'现金',500);
INSERT INTO category(category_id,category_name,category_level) VALUES(1,'校园闲置','1');
INSERT INTO category(category_id,category_name,category_level,parent_category_id) VALUES(2,'教材','2',1);
INSERT INTO product_info(product_id,user_id,category_id,product_name,campus,original_price,discount_price,stock_quantity) VALUES(1,2,2,'测试教材','东校区',100,30,2);
