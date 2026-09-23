package com.tian.textbook.unit.importexport;

import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.service.ImportRowWriter;
import com.tian.textbook.importexport.support.ImportRunContext;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.tian.textbook.textbook.mapper.CourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 名单导入「初始密码预哈希」范围单元测试（B-G2④）。
 *
 * <p>已存在账号的密码不因导入而改变，因此**只为本批新建的账号**算 BCrypt：
 * 此前对文件内全部 user_no 预哈希，重复导入一份全量名单（绝大多数行是已存在账号）
 * 要白付一遍全量 BCrypt CPU（单次 60-100ms，万行级即分钟级浪费）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImportPasswordHashScopeTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private UserSemesterProfileMapper profileMapper;
    @Mock
    private SchoolClassMapper schoolClassMapper;
    @Mock
    private TextbookMapper textbookMapper;
    @Mock
    private CourseMapper courseMapper;
    @Mock
    private TeacherCourseMapper teacherCourseMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ImportRunContext ctx;

    private ImportRowWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ImportRowWriter(userMapper, userRoleMapper, profileMapper, schoolClassMapper,
                textbookMapper, courseMapper, teacherCourseMapper, passwordEncoder);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$hashed");
        when(ctx.roleId("STUDENT")).thenReturn(null);
        when(ctx.semesterId()).thenReturn(null);
        when(ctx.writeUserAffiliation()).thenReturn(false);
    }

    private StudentImportRow row(String userNo) {
        StudentImportRow row = new StudentImportRow();
        row.setUserNo(userNo);
        row.setName("学生" + userNo);
        return row;
    }

    private SysUser existing(String userNo) {
        SysUser user = new SysUser();
        user.setId(99L);
        user.setUserNo(userNo);
        return user;
    }

    @Test
    @DisplayName("只为新建账号预哈希：已存在账号一次 BCrypt 都不算（B-G2④）")
    void hashesOnlyNewAccounts() {
        when(ctx.user("20230001")).thenReturn(null);              // 新账号
        when(ctx.user("20230002")).thenReturn(existing("20230002")); // 已存在

        writer.writeStudentRows(List.of(row("20230001"), row("20230002")), ctx);

        // 只对 20230001 算一次；20230002 不产生任何 BCrypt 计算
        verify(passwordEncoder, times(1)).encode(anyString());
        verify(passwordEncoder, times(1)).encode("230001");   // 学号后 6 位
        verify(passwordEncoder, never()).encode("230002");
    }

    @Test
    @DisplayName("全部已存在（重复导入全量名单）：零 BCrypt 计算")
    void allExisting_noHashing() {
        when(ctx.user("20230001")).thenReturn(existing("20230001"));
        when(ctx.user("20230002")).thenReturn(existing("20230002"));

        writer.writeStudentRows(List.of(row("20230001"), row("20230002")), ctx);

        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("同一新学号重复多行：只算一次（去重后预哈希）")
    void duplicatedNewRows_hashedOnce() {
        when(ctx.user("20230001")).thenReturn(null);

        writer.writeStudentRows(List.of(row("20230001"), row("20230001")), ctx);

        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    @DisplayName("新建账号仍写入初始密码哈希（规则不变：学号后 6 位）")
    void newAccount_stillGetsHash() {
        when(ctx.user("20230001")).thenReturn(null);
        org.mockito.Mockito.doAnswer(inv -> {
            SysUser created = inv.getArgument(0);
            created.setId(7L);
            return 1;
        }).when(userMapper).insert(any(SysUser.class));

        writer.writeStudentRows(List.of(row("20230001")), ctx);

        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$hashed");
    }
}
