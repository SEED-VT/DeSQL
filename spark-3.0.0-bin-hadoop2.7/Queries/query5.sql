Select * from customer_demographics where cd_gender = 'F' AND cd_demo_sk IN ( select c_current_cdemo_sk from customer where c_birth_year > 1980 and c_birth_month < 6)