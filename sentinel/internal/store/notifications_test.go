package store

import (
	"context"
	"testing"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/notify"
)

func TestNotificationsAreIdempotentAndPerUser(t *testing.T) {
	p := open(t)
	ctx := context.Background()
	run := int(time.Now().UnixNano() % 1e8)
	alice, bob := uuid(run, 1), uuid(run, 2)
	e := notify.Event{ID: uuid(run, 10), UserID: alice, Kind: "PIX_RECEIVED", Title: "Pix recebido",
		Body: "R$ 10,00", Link: "/app/extrato", CreatedAt: time.Now()}

	first, created, err := p.SaveEvent(ctx, e)
	if err != nil || !created {
		t.Fatal(err, created)
	}
	again, created, err := p.SaveEvent(ctx, e)
	if err != nil || created || again.Seq != first.Seq {
		t.Fatalf("repetido virou outro aviso: %v %v %d %d", err, created, again.Seq, first.Seq)
	}
	if _, _, err := p.SaveEvent(ctx, notify.Event{ID: uuid(run, 11), UserID: bob, Kind: "PIX_SENT",
		Title: "Pix enviado", CreatedAt: time.Now()}); err != nil {
		t.Fatal(err)
	}

	items, unread, err := p.Recent(ctx, alice, 30)
	if err != nil || len(items) != 1 || unread != 1 || items[0].Link != "/app/extrato" {
		t.Fatalf("%v %d %+v", err, unread, items)
	}
	if missed, err := p.Since(ctx, alice, first.Seq-1, 10); err != nil || len(missed) != 1 {
		t.Fatalf("%v %d", err, len(missed))
	}
	if err := p.MarkRead(ctx, alice, first.Seq, time.Now()); err != nil {
		t.Fatal(err)
	}
	if _, unread, _ := p.Recent(ctx, alice, 30); unread != 0 {
		t.Fatalf("ainda %d não lidos", unread)
	}
	if _, unread, _ := p.Recent(ctx, bob, 30); unread != 1 {
		t.Fatalf("mexeu nos avisos do outro: %d", unread)
	}
}
