// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include <errno.h>  // errno, ENAMETOOLONG
#include <limits.h>
#include <string.h>  // strerror
#include <sys/cygwin.h>
#include <sys/socket.h>
#include <sys/statfs.h>
#include <unistd.h>

#include <windows.h>

#include <cstdlib>
#include <cstdio>

#include "src/main/cpp/blaze_util.h"
#include "src/main/cpp/blaze_util_platform.h"
#include "src/main/cpp/util/errors.h"
#include "src/main/cpp/util/exit_code.h"
#include "src/main/cpp/util/file.h"
#include "src/main/cpp/util/strings.h"

namespace blaze {

using blaze_util::die;
using blaze_util::pdie;
using std::string;
using std::vector;

void WarnFilesystemType(const string& output_base) {
}

string GetSelfPath() {
  char buffer[PATH_MAX] = {};
  if (!GetModuleFileName(0, buffer, sizeof(buffer))) {
    pdie(255, "Error %u getting executable file name\n", GetLastError());
  }
  return string(buffer);
}

string GetOutputRoot() {
  char* tmpdir = getenv("TMPDIR");
  if (tmpdir == 0 || strlen(tmpdir) == 0) {
    return "/var/tmp";
  } else {
    return string(tmpdir);
  }
}

pid_t GetPeerProcessId(int socket) {
  struct ucred creds = {};
  socklen_t len = sizeof creds;
  if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &creds, &len) == -1) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
         "can't get server pid from connection");
  }
  return creds.pid;
}

uint64_t MonotonicClock() {
  struct timespec ts = {};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

uint64_t ProcessClock() {
  struct timespec ts = {};
  clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &ts);
  return ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void SetScheduling(bool batch_cpu_scheduling, int io_nice_level) {
  // TODO(bazel-team): There should be a similar function on Windows.
}

string GetProcessCWD(int pid) {
  char server_cwd[PATH_MAX] = {};
  if (readlink(
          ("/proc/" + ToString(pid) + "/cwd").c_str(),
          server_cwd, sizeof(server_cwd)) < 0) {
    return "";
  }

  return string(server_cwd);
}

bool IsSharedLibrary(const string &filename) {
  return blaze_util::ends_with(filename, ".dll");
}

string GetDefaultHostJavabase() {
  const char *javahome = getenv("JAVA_HOME");
  if (javahome == NULL) {
    die(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR,
        "Error: JAVA_HOME not set.");
  }
  return javahome;
}

namespace {
void ReplaceAll(
        std::string* s, const std::string& pattern, const std::string with) {
  size_t pos = 0;
  while (true) {
    size_t pos = s->find(pattern, pos);
    if (pos == std::string::npos) return;
    *s = s->replace(pos, pattern.length(), with);
    pos += with.length();
  }
}

// Max command line length is per CreateProcess documentation
// (https://msdn.microsoft.com/en-us/library/ms682425(VS.85).aspx)
static const int MAX_CMDLINE_LENGTH = 32768;

struct CmdLine {
  char cmdline[MAX_CMDLINE_LENGTH];
};
static void CreateCommandLine(CmdLine* result, const string& exe,
                              const vector<string>& args_vector) {
  string cmdline;
  bool first = true;
  for (const auto& s : args_vector) {
    if (first) {
      first = false;
      // Skip first argument, instead use quoted executable name with ".exe"
      // suffix.
      cmdline.append("\"");
      cmdline.append(exe);
      cmdline.append(".exe");
      cmdline.append("\"");
      continue;
    } else {
      cmdline.append(" ");
    }

    string arg = s;
    // Quote quotes.
    if (s.find("\"") != string::npos) {
      ReplaceAll(&arg, "\"", "\\\"");
    }

    // Quotize spaces.
    if (arg.find(" ") != string::npos) {
      cmdline.append("\"");
      cmdline.append(arg);
      cmdline.append("\"");
    } else {
      cmdline.append(arg);
    }
  }

  if (cmdline.length() >= max_len) {
    pdie(blaze_exit_code::INTERNAL_ERROR,
         "Command line too long: %s", cmdline.c_str());
  }

  // Copy command line into a mutable buffer.
  // CreateProcess is allowed to mutate its command line argument.
  strncpy(result->cmdline, cmdline.c_str(), MAX_CMDLINE_LENGTH - 1);
  result[MAX_CMDLINE_LENGTH - 1] = 0;
}

}  // namespace

string RunProgram(
    const string& exe, const vector<string>& args_vector) {
  SECURITY_ATTRIBUTES sa = {0};

  sa.nLength = sizeof(SECURITY_ATTRIBUTES);
  sa.bInheritHandle = TRUE;
  sa.lpSecurityDescriptor = NULL;

  HANDLE pipe_read, pipe_write;
  if (!CreatePipe(&pipe_read, &pipe_write, &sa, 0)) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "CreatePipe");
  }

  if (!SetHandleInformation(pipe_read, HANDLE_FLAG_INHERIT, 0)) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "SetHandleInformation");
  }

  PROCESS_INFORMATION processInfo = {0};
  STARTUPINFO startupInfo = {0};

  startupInfo.hStdError = pipe_write;
  startupInfo.hStdOutput = pipe_write;
  startupInfo.dwFlags |= STARTF_USESTDHANDLES;
  CmdLine cmdline;
  CreateCommandLine(&cmdline, exe, args_vector);

  bool ok = CreateProcess(
      NULL,           // _In_opt_    LPCTSTR               lpApplicationName,
      //                 _Inout_opt_ LPTSTR                lpCommandLine,
      cmdline.cmdline,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpProcessAttributes,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpThreadAttributes,
      true,           // _In_        BOOL                  bInheritHandles,
      0,              // _In_        DWORD                 dwCreationFlags,
      NULL,           // _In_opt_    LPVOID                lpEnvironment,
      NULL,           // _In_opt_    LPCTSTR               lpCurrentDirectory,
      &startupInfo,   // _In_        LPSTARTUPINFO         lpStartupInfo,
      &processInfo);  // _Out_       LPPROCESS_INFORMATION lpProcessInformation

  if (!ok) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "CreateProcess");
  }

  CloseHandle(pipe_write);
  std::string result = "";
  DWORD bytes_read;
  CHAR buf[1024];

  for (;;) {
    ok = ::ReadFile(pipe_read, buf, 1023, &bytes_read, NULL);
    if (!ok || bytes_read == 0) {
      break;
    }
    buf[bytes_read] = 0;
    result = result + buf;
  }

  CloseHandle(pipe_read);
  CloseHandle(processInfo.hProcess);
  CloseHandle(processInfo.hThread);

  return result;
}

// If we pass DETACHED_PROCESS to CreateProcess(), cmd.exe appropriately
// returns the command prompt when the client terminates. msys2, however, in
// its infinite wisdom, waits until the *server* terminates and cannot be
// convinced otherwise.
//
// So, we first pretend to be a POSIX daemon so that msys2 knows about our
// intentions and *then* we call CreateProcess(). Life ain't easy.
static bool DaemonizeOnWindows() {
  if (fork() > 0) {
    // We are the original client process.
    return true;
  }

  if (fork() > 0) {
    // We are the child of the original client process. Terminate so that the
    // actual server is not a child process of the client.
    exit(0);
  }

  setsid();
  // Contrary to the POSIX version, we are not closing the three standard file
  // descriptors here. CreateProcess() will take care of that and it's useful
  // to see the error messages in ExecuteDaemon() on the console of the client.
  return false;
}

int ExecuteDaemon(const string& exe, const std::vector<string>& args_vector,
                   const string& daemon_output, const string& pid_file) {
  if (DaemonizeOnWindows()) {
    // We are the client process
    // TODO(lberki): -1 is only an in-band signal that tells that there is no
    // way we can tell if the server process is still alive. Fix this, because
    // otherwise if any of the calls below fails, the client will hang until
    // it times out.
    return -1;
  }

  SECURITY_ATTRIBUTES sa;

  sa.nLength = sizeof(SECURITY_ATTRIBUTES);
  sa.bInheritHandle = TRUE;
  sa.lpSecurityDescriptor = NULL;

  HANDLE output_file;

  if (!CreateFile(
      daemon_output.c_str(),
      GENERIC_READ | GENERIC_WRITE,
      0,
      &sa,
      CREATE_ALWAYS,
      FILE_ATTRIBUTE_NORMAL,
      NULL)) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "CreateFile");
  }

  HANDLE pipe_read, pipe_write;
  if (!CreatePipe(&pipe_read, &pipe_write, &sa, 0)) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "CreatePipe");
  }

  if (!SetHandleInformation(pipe_write, HANDLE_FLAG_INHERIT, 0)) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "SetHandleInformation");
  }

  PROCESS_INFORMATION processInfo = {0};
  STARTUPINFO startupInfo = {0};

  startupInfo.hStdInput = pipe_read;
  startupInfo.hStdError = output_file;
  startupInfo.hStdOutput = output_file;
  startupInfo.dwFlags |= STARTF_USESTDHANDLES;
  CmdLine cmdline;
  CreateCommandLine(&cmdline, exe, args_vector);

  // Propagate BAZEL_SH environment variable to a sub-process.
  // todo(dslomov): More principled approach to propagating
  // environment variables.
  SetEnvironmentVariable("BAZEL_SH", getenv("BAZEL_SH"));

  bool ok = CreateProcess(
      NULL,           // _In_opt_    LPCTSTR               lpApplicationName,
      //                 _Inout_opt_ LPTSTR                lpCommandLine,
      cmdline.cmdline,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpProcessAttributes,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpThreadAttributes,
      TRUE,           // _In_        BOOL                  bInheritHandles,
      //                 _In_        DWORD                 dwCreationFlags,
      DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP,
      NULL,           // _In_opt_    LPVOID                lpEnvironment,
      NULL,           // _In_opt_    LPCTSTR               lpCurrentDirectory,
      &startupInfo,   // _In_        LPSTARTUPINFO         lpStartupInfo,
      &processInfo);  // _Out_       LPPROCESS_INFORMATION lpProcessInformation

  if (!ok) {
    pdie(blaze_exit_code::LOCAL_ENVIRONMENTAL_ERROR, "CreateProcess");
  }

  CloseHandle(output_file);
  CloseHandle(pipe_write);
  CloseHandle(pipe_read);

  WriteFile(ToString(GetProcessId(processInfo.hProcess)), pid_file);
  CloseHandle(processInfo.hProcess);
  CloseHandle(processInfo.hThread);

  exit(0);
}

// Run the given program in the current working directory,
// using the given argument vector.
void ExecuteProgram(
    const string& exe, const vector<string>& args_vector) {
  CmdLine cmdline;
  CreateCommandLine(&cmdline, exe, args_vector);

  STARTUPINFO startupInfo = {0};
  PROCESS_INFORMATION processInfo = {0};

  // Propagate BAZEL_SH environment variable to a sub-process.
  // todo(dslomov): More principled approach to propagating
  // environment variables.
  SetEnvironmentVariable("BAZEL_SH", getenv("BAZEL_SH"));

  bool success = CreateProcess(
      NULL,           // _In_opt_    LPCTSTR               lpApplicationName,
      //                 _Inout_opt_ LPTSTR                lpCommandLine,
      cmdline.cmdline,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpProcessAttributes,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpThreadAttributes,
      true,           // _In_        BOOL                  bInheritHandles,
      0,              // _In_        DWORD                 dwCreationFlags,
      NULL,           // _In_opt_    LPVOID                lpEnvironment,
      NULL,           // _In_opt_    LPCTSTR               lpCurrentDirectory,
      &startupInfo,   // _In_        LPSTARTUPINFO         lpStartupInfo,
      &processInfo);  // _Out_       LPPROCESS_INFORMATION lpProcessInformation

  if (!success) {
    pdie(255, "Error %u executing: %s\n", GetLastError(), cmdline);
  }
  WaitForSingleObject(processInfo.hProcess, INFINITE);
  DWORD exit_code;
  GetExitCodeProcess(processInfo.hProcess, &exit_code);
  CloseHandle(processInfo.hProcess);
  CloseHandle(processInfo.hThread);
  exit(exit_code);
}

string ListSeparator() { return ";"; }

string ConvertPath(const string& path) {
  char* wpath = static_cast<char*>(cygwin_create_path(
      CCP_POSIX_TO_WIN_A, static_cast<const void*>(path.c_str())));
  string result(wpath);
  free(wpath);
  return result;
}

bool SymlinkDirectories(const string &target, const string &link) {
  const string target_win = ConvertPath(target);
  const string link_win = ConvertPath(link);
  vector<string> args;
  args.push_back("cmd");
  args.push_back("/C");
  args.push_back("mklink");
  args.push_back("/J");
  args.push_back(link_win);
  args.push_back(target_win);

  CmdLine cmdline;
  CreateCommandLine(&cmdline, "cmd", args);

  STARTUPINFO startupInfo = {0};
  PROCESS_INFORMATION processInfo = {0};

  bool success = CreateProcess(
      NULL,           // _In_opt_    LPCTSTR               lpApplicationName,
      //                 _Inout_opt_ LPTSTR                lpCommandLine,
      cmdline.cmdline,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpProcessAttributes,
      NULL,           // _In_opt_    LPSECURITY_ATTRIBUTES lpThreadAttributes,
      true,           // _In_        BOOL                  bInheritHandles,
      0,              // _In_        DWORD                 dwCreationFlags,
      NULL,           // _In_opt_    LPVOID                lpEnvironment,
      NULL,           // _In_opt_    LPCTSTR               lpCurrentDirectory,
      &startupInfo,   // _In_        LPSTARTUPINFO         lpStartupInfo,
      &processInfo);  // _Out_       LPPROCESS_INFORMATION lpProcessInformation

  if (!success) {
    pdie(255, "Error %u executing: %s\n", GetLastError(), cmdline);
  }

  WaitForSingleObject(processInfo.hProcess, INFINITE);
  DWORD exit_code;
  GetExitCodeProcess(processInfo.hProcess, &exit_code);
  CloseHandle(processInfo.hProcess);
  CloseHandle(processInfo.hThread);
  return exit_code == 0;
}

}  // namespace blaze
