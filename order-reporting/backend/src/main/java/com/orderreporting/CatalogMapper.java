package com.orderreporting;
import org.apache.ibatis.annotations.*;
import java.util.List;
import com.orderreporting.Model.*;

@Mapper
public interface CatalogMapper {
    @Select("SELECT id,username,password_hash,role,store_id FROM users WHERE username=#{username}")
    Account account(String username);
    @Select("<script>SELECT id,code,name FROM stores <if test='storeId != null'>WHERE id=#{storeId}</if> ORDER BY id</script>")
    List<Catalog> stores(Long storeId);
    @Select("SELECT id,code,name FROM products ORDER BY id") List<Catalog> products();
    @Insert("INSERT INTO users(username,password_hash,role,store_id) VALUES(#{username},#{hash},#{role},#{storeId})")
    void addUser(String username, String hash, String role, Long storeId);
    @Insert("INSERT INTO stores(code,name) VALUES(#{code},#{name})") void addStore(String code, String name);
    @Insert("INSERT INTO products(code,name) VALUES(#{code},#{name})") void addProduct(String code, String name);
}
