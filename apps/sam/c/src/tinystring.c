#include "tinystring.h"

#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <malloc.h>
#define _GNU_SOURCE
#include <string.h>

#ifndef min
#define min(a,b) ((a) > (b) ? (b) : (a))
#endif

extern char *strndup(const char *s, size_t n);


struct string_s {
    const char* data;
    long int size;
    bool _no_del; //SIGH...
};

string_t string_ncreate(const char* cstr,long int length) {
    string_t self = malloc(sizeof(struct string_s));
    self->size = length;
    if(cstr) self->data = strndup(cstr,length);
    else self->data = NULL;
    self->_no_del = 0;
    return self;
}

string_t string_create(const char* cstr) {
    if(!cstr)
        return string_ncreate(NULL, 0);
    return string_ncreate(cstr, strlen(cstr));
}

string_t string_nwrap(const char* cstr, long int length) {
    static struct string_s self;
    self.size = length;
    self.data = cstr;
    self._no_del = 1;
    return &self;
}

string_t string_wrap(const char* cstr) {
    if(!cstr)
        return string_nwrap(NULL, 0);
    return string_nwrap(cstr, strlen(cstr));
}

string_t string_fmt(const char* fmt, ...) {
    va_list args;
    FILE* tmp = tmpfile();
    string_t self = malloc(sizeof(struct string_s));
    char* data;
    va_start(args, fmt);
    vfprintf(tmp, fmt, args);
    va_end(args);

    self->size = ftell(tmp);

    rewind(tmp);
    data = malloc(self->size);
    fread(data, self->size, sizeof(char), tmp);

    fclose(tmp);
    self->data = data;
    return self;
}

string_t string_cat(string_t head,string_t tail) {
    //There are two ways to skin a cat...
    string_t self = malloc(sizeof(struct string_s));
    char* data;
    self->size = head->size+tail->size;
    data = malloc(self->size);
    memcpy(data, head->data, head->size);
    memcpy(data+head->size,tail->data,tail->size);
    self->data = data;
    return self;
}

/* Source Dest */
void string_copy(string_t src,string_t dest) {
    dest->data = realloc((char*)dest->data,src->size);
    memcpy((char*)dest->data,src->data,dest->size);
}

void string_copy_raw(string_t src, void* dest,size_t size) {
    size = min(src->size,size);
    memcpy(dest,src->data,size);
    ((char*)dest)[size] = '\0';
}

const char* string_data(string_t self) {
    return self->data;
}

long int string_size(string_t self) {
    return self->size;
}

void string_free(string_t self) {
    if(!self->_no_del)
        free((char*)self->data);

    free(self);
}

#ifndef min
#define min(a,b) ((a) < (b) ? (a) : (b))
#endif

bool string_equal(string_t this,string_t that) {
    return !memcmp(this->data,that->data,min(this->size,that->size));
}

bool string_equali(string_t this,string_t that) {
    return !strncasecmp(this->data,that->data,min(this->size,that->size));
}

int string_cmp(string_t this,string_t that) {
    return memcmp(this->data,that->data,min(this->size,that->size));
}

int string_cmpi(string_t this,string_t that) {
    return strncasecmp(this->data,that->data,min(this->size,that->size));
}
