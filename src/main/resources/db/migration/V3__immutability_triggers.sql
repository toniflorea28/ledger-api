CREATE TRIGGER transactions_are_immutable_upd BEFORE UPDATE ON transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions are immutable';

CREATE TRIGGER transactions_are_immutable_del BEFORE DELETE ON transactions FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'transactions are immutable';

CREATE TRIGGER entries_are_immutable_upd BEFORE UPDATE ON entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entries are immutable';

CREATE TRIGGER entries_are_immutable_del BEFORE DELETE ON entries FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'entries are immutable';
